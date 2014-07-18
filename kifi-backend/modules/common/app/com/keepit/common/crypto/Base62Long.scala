package com.keepit.common.crypto

/**
 * Encodes `Long` values to 11-character case-sensitive alphanumeric strings.
 * `decode(encode(l))` will always be `l`. `encode(decode(s))` may not be `s`.
 */
object Base62Long {

  val Encode: Array[Char] = Array(
    'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
    'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
    'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
    'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9');

  val Decode: Array[Byte] = Array(
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 52, 53, 54,
    55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -1, -1, -1, -1, 0, 1, 2, 3, 4,
    5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
    24, 25, -1, -1, -1, -1, 63, -1, 26, 27, 28, 29, 30, 31, 32, 33, 34,
    35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51);

  /** Encodes a 64-bit number to an 11-character alphanumeric string. */
  def encode(value: Long): String = {
    val neg = value < 0;
    val nn = if (neg) value ^ -1 else value;

    val q0 = nn / 62; val r0 = (nn % 62).toInt;
    val q1 = q0 / 62; val r1 = (q0 % 62).toInt;
    val q2 = q1 / 62; val r2 = (q1 % 62).toInt;
    val q3 = q2 / 62; val r3 = (q2 % 62).toInt;
    val q4 = q3 / 62; val r4 = (q3 % 62).toInt;
    val q5 = (q4 / 62).toInt; val r5 = (q4 % 62).toInt;
    val q6 = q5 / 62; val r6 = q5 % 62;
    val q7 = q6 / 62; val r7 = q6 % 62;
    val q8 = q7 / 62; val r8 = q7 % 62;
    val q9 = q8 / 62; val r9 = q8 % 62;
    val t9 = if (neg) 61 - q9 else q9

    Array(Encode(t9),
      Encode(r9), Encode(r8), Encode(r7), Encode(r6), Encode(r5),
      Encode(r4), Encode(r3), Encode(r2), Encode(r1), Encode(r0)).mkString
  }

  /**
   * Decodes an 11-character alphanumeric string to a 64-bit number.
   * For efficiency, does not validate input strings. Behavior is unspecified
   * for invalid inputs: a value may be returned or an exception may be thrown.
   */
  def decode(value: String): Long = {
    val arr = value.toCharArray
    val r0 = Decode(arr(10))
    val r1 = Decode(arr(9))
    val r2 = Decode(arr(8))
    val r3 = Decode(arr(7))
    val r4 = Decode(arr(6))
    val r5 = Decode(arr(5))
    val r6 = Decode(arr(4))
    val r7 = Decode(arr(3))
    val r8 = Decode(arr(2))
    val r9 = Decode(arr(1))
    val t9 = Decode(arr(0))
    val neg = t9 > 30
    val q9 = if (neg) 61 - t9 else t9
    val q5: Int = r6 + 62 * (r7 + 62 * (r8 + 62 * (r9 + 62 * q9)))
    val nn: Long = r0 + 62 * (r1 + 62 * (r2 + 62 * (r3 + 62 * (r4 + 62 * (r5 + 62L * q5)))))
    if (neg) nn ^ -1 else nn
  }

}
