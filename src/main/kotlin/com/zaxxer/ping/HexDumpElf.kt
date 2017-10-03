package com.zaxxer.ping

import java.util.Formatter

/**
 * Hex Dump Elf
 *
 * xxxx: 00 11 22 33 44 55 66 77   88 99 aa bb cc dd ee ff ................
 */
object HexDumpElf {
   private val MAX_VISIBLE = 127
   private val MIN_VISIBLE = 31

   /**
    * @param displayOffset the display offset (left column)
    * @param data the byte array of data
    * @param offset the offset to start dumping in the byte array
    * @param len the length of data to dump
    * @return the dump string
    */
   fun dump(displayOffset : Int, data : ByteArray, offset : Int, len : Int) : String {
      val sb = StringBuilder()
      val formatter = Formatter(sb)
      val ascii = StringBuilder()

      var dataNdx = offset
      val maxDataNdx = offset + len
      val lines = (len + 16) / 16
      for (i in 0 until lines) {
         ascii.append(" |")
         formatter.format("%08x  ", displayOffset + i * 16)

         for (j in 0..15) {
            if (dataNdx < maxDataNdx) {
               val b = data[dataNdx++]
               formatter.format("%02x ", b)
               ascii.append(if (b > MIN_VISIBLE && b < MAX_VISIBLE) b.toChar() else ' ')
            }
            else {
               sb.append("   ")
            }

            if (j == 7) {
               sb.append(' ')
            }
         }

         ascii.append('|')
         sb.append(ascii).append('\n')
         ascii.setLength(0)
      }

      formatter.close()
      return sb.toString()
   }
}// private constructor
