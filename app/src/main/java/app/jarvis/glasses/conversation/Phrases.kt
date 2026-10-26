package app.jarvis.glasses.conversation

/**
 * The two things the app decides from what was heard before asking Jarvis: whether the question
 * is about what the wearer sees (take a photo with it), and whether they are ending the
 * conversation (no answer is paid for). English and Malay, as the owner speaks both.
 */
object Phrases {
  private val LOOK =
      listOf(
          "what am i looking at",
          "what i'm looking at",
          "what is this",
          "what's this",
          "what is that",
          "what's that",
          "look at this",
          "look at that",
          "take a look",
          "can you see this",
          "do you see this",
          "in front of me",
          "read this",
          "read that",
          "what does this say",
          "what does it say",
          "identify this",
          // Malay
          "apa ni",
          "apa ini",
          "apa tu",
          "apa itu",
          "apa benda",
          "tengok ni",
          "tengok ini",
          "tengok tu",
          "lihat ini",
          "baca ni",
          "baca ini",
          "depan saya",
          "depan aku",
      )

  private val STOP =
      setOf(
          "stop",
          "stop jarvis",
          "that's all",
          "that is all",
          "that's it",
          "nothing",
          "nothing else",
          "no thanks",
          "no thank you",
          "thanks",
          "thank you",
          "thanks jarvis",
          "thank you jarvis",
          "ok thanks",
          "okay thanks",
          "bye",
          "goodbye",
          "bye jarvis",
          "cancel",
          "never mind",
          "nevermind",
          // Malay
          "dah",
          "sudah",
          "cukup",
          "tak ada",
          "takde",
          "tak apa",
          "terima kasih",
          "terima kasih jarvis",
          "ok terima kasih",
      )

  fun wantsPhoto(heard: String): Boolean {
    // Whole words only: "apa tu" is not in "apa tujuan".
    val t = " ${normalise(heard)} "
    return LOOK.any { t.contains(" $it ") }
  }

  private val ACK =
      setOf(
          "ok",
          "okay",
          "okay okay",
          "alright",
          "all right",
          "right",
          "i see",
          "aha",
          "ah",
          "uh huh",
          "mm",
          "hmm",
          "mhm",
          "good",
          "great",
          "nice",
          "cool",
          "got it",
          "ага", // "aha", heard as Russian
          // Malay
          "oh",
          "ok lah",
          "baik",
          "faham",
      )

  /** A bare acknowledgement after an answer: keep listening, ask nothing ("Okay." re-asked the last question). */
  fun isAck(heard: String): Boolean = normalise(heard) in ACK

  /**
   * "Hey Meta, open Jarvis" heard by Jarvis itself, said again during a conversation: not a question
   * (the recogniser often hears "hi" or "a" for "hey").
   */
  fun isLaunchPhrase(heard: String): Boolean {
    val t = normalise(heard)
    return t.contains("meta") && Regex("\\b(open|start|launch)( the)? jarvis\\b").containsMatchIn(t)
  }

  /** Only a short utterance that is wholly a closing: "thanks, and what's the weather" is a question. */
  fun isStop(heard: String): Boolean = normalise(heard) in STOP

  private fun normalise(s: String): String =
      s.lowercase().replace('’', '\'').replace(Regex("[^\\p{L}\\p{N}' ]"), " ").replace(Regex("\\s+"), " ").trim()
}
