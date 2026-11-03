package app.jarvis.glasses.watch

import app.jarvis.glasses.hub.Hub
import app.jarvis.glasses.jarvis.Turn
import app.jarvis.glasses.jarvis.VoiceEvent
import app.jarvis.link.Act
import app.jarvis.link.Approve
import app.jarvis.link.Ask
import app.jarvis.link.Fetch
import app.jarvis.link.Hello
import app.jarvis.link.Reply
import app.jarvis.link.Stop
import app.jarvis.link.WatchLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The watch's questions, asked of Jarvis by this phone with its own sign-in ([WatchLink]). Each
 * streams back as it comes: progress, the answer's words, and when the watch speaks, its voice a
 * sentence at a time; then [Reply.Done]. What was answered is kept in Chat, as Talk keeps it: not
 * a failure, nor what Jarvis found wasn't said to it.
 *
 * What the wrist shows and does ([Fetch], [Act], [Approve]) goes to the [wrist], each answered
 * with one reply.
 *
 * [send] is the Data Layer's; it doesn't throw, since a sentence too big for one message is better
 * left out than the rest of the answer with it. A watch that asks again hangs up its last question
 * first ([Stop]).
 */
class WatchRelay(
    private val scope: CoroutineScope,
    /** Whether this phone can ask Jarvis, and as whom; null when it isn't signed in. */
    private val signedInAs: () -> String?,
    private val ask: suspend (Ask, onEvent: suspend (VoiceEvent) -> Unit) -> VoiceEvent.Answer,
    private val keep: (List<Turn>) -> Unit,
    private val send: suspend (node: String, Reply) -> Unit,
    private val wrist: Wrist? = null,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
    /** Whether anything is on its way: the app is kept running meanwhile ([WatchAskService]). */
    private val busy: (Boolean) -> Unit = {},
) {
  private val asking = mutableMapOf<String, Job>()

  fun onMessage(node: String, path: String, data: ByteArray) {
    when (val m = WatchLink.fromWatch(path, data)) {
      is Hello -> scope.launch { send(node, signedInAs().let { Reply.Ready(m.id, it != null, it.orEmpty()) }) }
      is Ask -> start(node, m)
      is Stop -> synchronized(asking) { asking.remove(m.id) }?.cancel()
      is Fetch -> forWrist(node, m.id) { w ->
        when (m.what) {
          Fetch.HOME -> Reply.Home(m.id, w.home())
          Fetch.APPROVALS -> Reply.Approvals(m.id, w.approvals())
          Fetch.GLANCE -> w.glance().let { (approvals, jobs) -> Reply.Glance(m.id, approvals, jobs) }
          else -> Reply.Failed(m.id, OLDER)
        }
      }
      is Act -> forWrist(node, m.id) { w -> Reply.Acted(m.id, w.act(m.panel, m.key, m.action, m.value, m.member)) }
      is Approve -> forWrist(node, m.id) { w -> w.approve(m.proposal, m.yes).let { Reply.Acted(m.id) } }
      null -> log("unread from the watch: $path", null)
    }
  }

  private fun start(node: String, q: Ask) {
    if (signedInAs() == null) {
      scope.launch { send(node, Reply.Failed(q.id, SIGN_IN)) }
      return
    }
    track(node, q.id) {
      // At once, before Chat is read and Jarvis is asked: the watch waits only so long for a first word.
      send(node, Reply.Progress(q.id, ""))
      val answer =
          ask(q) { e ->
            when (e) {
              is VoiceEvent.Progress -> send(node, Reply.Progress(q.id, e.text))
              is VoiceEvent.Answer -> send(node, Reply.Answer(q.id, e.text, e.ok, e.notForMe))
              is VoiceEvent.Audio -> if (q.speak) send(node, Reply.Audio(q.id, e.seq, e.last, e.mime, e.base64))
              is VoiceEvent.Heard -> Unit
            }
          }
      if (answer.ok && !answer.notForMe && answer.text.isNotBlank()) keep(listOf(Turn.user(q.text), Turn.jarvis(answer.text)))
      send(node, Reply.Done(q.id))
    }
  }

  private fun forWrist(node: String, id: String, reply: suspend (Wrist) -> Reply) {
    val w = wrist
    if (w == null || signedInAs() == null) {
      scope.launch { send(node, Reply.Failed(id, if (w == null) OLDER else SIGN_IN)) }
      return
    }
    track(node, id) { send(node, reply(w)) }
  }

  /** [work] for the watch, under [id]: one at a time per id, [Stop]pable, a failure sent back as [Reply.Failed]. */
  private fun track(node: String, id: String, work: suspend () -> Unit) {
    // Started once it is in [asking], so one that ends at once still finds itself there to leave.
    val job =
        scope.launch(start = CoroutineStart.LAZY) {
          try {
            work()
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            log("the watch's $id failed", e)
            send(node, Reply.Failed(id, if (e is Hub.NotAPerson) PERSON else e.message ?: "Jarvis couldn't be asked"))
          } finally {
            val idle = synchronized(asking) { if (asking[id] === coroutineContext[Job]) asking.remove(id); asking.isEmpty() }
            if (idle) busy(false)
          }
        }
    val (was, first) = synchronized(asking) { asking.put(id, job) to (asking.size == 1) }
    was?.cancel()
    if (first) busy(true)
    job.start()
  }

  private companion object {
    const val SIGN_IN = "Sign in to Jarvis in the phone app"
    const val PERSON = "Sign in to Jarvis with a code in the phone app to see this"
    const val OLDER = "The phone app can't show that yet: update it"
  }
}
