package spray.io

import akka.io._
import scala.collection.immutable.Queue
import akka.io.Tcp.NoAck
import scala.annotation.tailrec
import akka.util.ByteString

/**
 * Automated back-pressure handling is based on the idea that pressure
 * is created by the consumer but experienced at the producer side. E.g.
 * for http that means that a too big number of incoming requests is the
 * ultimate cause of an experienced bottleneck on the response sending side.
 *
 * The principle of applying back-pressure means that the best way of handling
 * pressure is by handling it at the root cause which means throttling the rate
 * at which work requests are coming in. That's the underlying assumption here:
 * work is generated on the incoming network side. If that's not true, e.g. when
 * the network stream is a truly bi-directional one (e.g. websockets) the strategy
 * presented here won't be optimal.
 *
 * How it works:
 *
 * No pressure:
 *   - forward all incoming data
 *   - send out ''n'' responses with NoAcks
 *   - send one response with Ack
 *   - once that ack was received we know all the former unacknowledged writes
 *     have been successful as well and don't need any further handling
 *
 * Pressure:
 *   - a Write fails, we know now that all former writes were successful, all
 *     latter ones, including the failed one were discarded (but we'll still receive CommandFailed
 *     messages for them as well)
 *   - the incoming side is informed to SuspendReading
 *   - we send ResumeWriting which is queued after all the Writes that will be discarded as well
 *   - once we receive WritingResumed go back to the no pressure mode and retry all of the buffered writes
 *   - we schedule a final write probe which will trigger ResumeReading when no lowWatermark is defined
 *   - once we receive the ack for that probe or the buffer size falls below a lowWatermark after
 *     an acknowledged Write, we ResumeReading
 *
 * Possible improvement:
 *   (see http://doc.akka.io/docs/akka/2.2.0-RC1/scala/io-tcp.html)
 *   - go into Ack based mode for a while after WritingResumed
 */
object BackPressureHandling {
  case class Ack(offset: Int) extends Tcp.Event
  object ResumeReadingNow extends Tcp.Event
  val ProbeForWriteQueueEmpty = Tcp.Write(ByteString.empty, ResumeReadingNow)

  def apply(ackRate: Int, lowWatermark: Int = Int.MaxValue): PipelineStage =
    new PipelineStage {
      def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
        new Pipelines with DynamicCommandPipeline with DynamicEventPipeline {
          val (initialEventPipeline, initialCommandPipeline) =
            writeThrough(new OutQueue(ackRate), isReading = true)

          def writeThrough(out: OutQueue, isReading: Boolean): (EPL, CPL) = {
            def resumeReading(): Unit = {
              commandPL(Tcp.ResumeReading)
              become(writeThrough(out, isReading = true))
            }
            def writeFailed(idx: Int): Unit = {
              out.dequeue(idx - 1)

              // go into buffering mode
              become(buffering(out, isReading))
            }

            val cpl: CPL = {
              case w @ Tcp.Write(data, NoAck(noAck)) ⇒
                if (noAck != null) context.log.warning(s"BackPressureHandling doesn't support custom NoAcks $noAck")

                commandPL(out.enqueue(w))
              case w @ Tcp.Write(data, ack) ⇒ commandPL(out.enqueue(w, forceAck = true))
              case c                        ⇒ commandPL(c)
            }

            val epl: EPL = {
              case Ack(idx) ⇒
                // dequeue and send out possible user level ack
                out.dequeue(idx).foreach(eventPL)

                if (!isReading && out.queue.length < lowWatermark) resumeReading()

              case Tcp.CommandFailed(Tcp.Write(_, NoAck(seq: Int))) ⇒ writeFailed(seq)
              case Tcp.CommandFailed(Tcp.Write(_, Ack(seq: Int))) ⇒ writeFailed(seq)
              case ResumeReadingNow ⇒ if (!isReading) resumeReading()
              case e ⇒ eventPL(e)
            }

            (epl, cpl)
          }

          def buffering(out: OutQueue, isReading: Boolean): (EPL, CPL) = {
            //context.log.warning(s"Now buffering with queue length ${out.queue.length}")

            if (isReading) commandPL(Tcp.SuspendReading)
            commandPL(Tcp.ResumeWriting)

            val epl: EPL = {
              case Tcp.WritingResumed ⇒
                // TODO: we are rebuilding the complete queue here to be sure all
                // the side-effects have been applied as well
                // This could be improved by reusing the internal data structures and
                // just executing the side-effects

                become(writeThrough(new OutQueue(ackRate, out.headSequenceNo), isReading = false))
                out.queue.foreach(commandPipeline) // commandPipeline is already in writeThrough state
                // we run one special probe writing request to make sure we will ResumeReading when the queue is empty
                commandPL(ProbeForWriteQueueEmpty)

              case Tcp.CommandFailed(_: Tcp.Write) ⇒ // drop this, this is expected
              case e                               ⇒ eventPL(e)
            }
            val cpl: CPL = {
              case w: Tcp.Write ⇒ out.enqueue(w)
              case c            ⇒ commandPL(c)
            }
            (epl, cpl)
          }

          def become(newPipeline: (EPL, CPL)): Unit = {
            eventPipeline.become(newPipeline._1)
            commandPipeline.become(newPipeline._2)
          }
        }
    }

  /** A mutable queue of outgoing write requests */
  class OutQueue(ackRate: Int, _initialSequenceNo: Int = 0) {
    // the current number of unacked Writes
    private[this] var unacked = 0
    // our buffer of sent but unacked Writes
    private[this] var buffer = Queue.empty[Tcp.Write]
    // the sequence number of the first Write in the buffer
    private[this] var firstSequenceNo = _initialSequenceNo

    def enqueue(w: Tcp.Write, forceAck: Boolean = false): Tcp.Write = {
      val seq = firstSequenceNo + buffer.length // is that efficient, otherwise maintain counter
      buffer = buffer.enqueue(w)

      val shouldAck = forceAck || unacked >= ackRate - 1

      // reset the counter whenever we Ack
      if (shouldAck) unacked = 0
      else unacked += 1

      val ack = if (shouldAck) Ack(seq) else NoAck(seq)
      Tcp.Write(w.data, ack)
    }
    @tailrec final def dequeue(upToSeq: Int): Option[Event] =
      if (firstSequenceNo < upToSeq) {
        firstSequenceNo += 1
        buffer = buffer.tail
        dequeue(upToSeq)
      } else if (firstSequenceNo == upToSeq) { // the last one may contain an ack to send
        firstSequenceNo += 1
        val front = buffer.front
        buffer = buffer.tail

        if (front.wantsAck) Some(front.ack)
        else None
      } else if (firstSequenceNo - 1 == upToSeq) None // first one failed
      else throw new IllegalStateException(s"Shouldn't get here, $firstSequenceNo > $upToSeq")

    def queue: Queue[Tcp.Write] = buffer
    def headSequenceNo = firstSequenceNo
  }
}
