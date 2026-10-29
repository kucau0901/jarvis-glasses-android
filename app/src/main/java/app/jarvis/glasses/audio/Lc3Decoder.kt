package app.jarvis.glasses.audio

/**
 * Google's LC3 decoder (app/src/main/cpp/liblc3), for microphones that send LC3: the Even
 * Realities G2's, in 10 ms frames at 16 kHz. Mono, 16-bit. [close] when done.
 */
class Lc3Decoder(frameUs: Int = 10_000, val rate: Int = 16_000) : AutoCloseable {
  private var handle = open(frameUs, rate)
  /** Samples in one frame (160 for 10 ms at 16 kHz). */
  val samples = rate * frameUs / 1_000_000
  private val frame = ShortArray(samples)

  init {
    check(handle != 0L) { "LC3 can't decode $frameUs µs at $rate Hz" }
  }

  /**
   * [bytes] holding whole frames of [frameBytes] each, decoded in order. A bad frame is concealed
   * (and counted in [concealed]) rather than dropped, so the timing holds.
   */
  fun decode(bytes: ByteArray, frameBytes: Int, length: Int = bytes.size): ShortArray {
    val frames = length / frameBytes
    val out = ShortArray(frames * samples)
    for (i in 0 until frames) {
      val r = decode(handle, bytes, i * frameBytes, frameBytes, frame)
      check(r >= 0) { "LC3 decode refused its arguments" }
      if (r == 1) concealed++
      frame.copyInto(out, i * samples)
    }
    return out
  }

  /** Frames that couldn't be read and were filled in. */
  var concealed = 0
    private set

  override fun close() {
    if (handle != 0L) close(handle)
    handle = 0L
  }

  private companion object {
    init {
      System.loadLibrary("jarvis_lc3")
    }

    @JvmStatic external fun open(frameUs: Int, rate: Int): Long

    @JvmStatic external fun decode(handle: Long, frame: ByteArray, offset: Int, length: Int, pcm: ShortArray): Int

    @JvmStatic external fun close(handle: Long)
  }
}
