/*
 * Copyright (C) 2017 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zaxxer.ping.impl.util

import java.nio.ByteBuffer
import java.util.Formatter

val VISIBLE_ASCII = 32..<127

internal fun dumpBuffer(message:String, buffer:ByteBuffer, offset:Int = 0) : String {
   val tmpBuffer = buffer.duplicate()
   tmpBuffer.position(offset)

   val bytes = ByteArray(tmpBuffer.remaining())
   tmpBuffer.get(bytes, 0, bytes.size)

   return StringBuilder()
         .append("   $message\n")
         .append(dump(0, bytes, 0, bytes.size))
         .toString()
}

/**
 * Hex dump a byte array
 *
 * xxxx: 00 11 22 33 44 55 66 77   88 99 aa bb cc dd ee ff ................
 *
 * @param displayOffset the display offset (left column)
 * @param data the byte array of data
 * @param offset the offset to start dumping in the byte array
 * @param len the length of data to dump
 * @return the dump string
 */
fun dump(displayOffset:Int, data:ByteArray, offset:Int, len:Int) : String {
   val sb = StringBuilder()
   val formatter = Formatter(sb)
   val ascii = StringBuilder()

   var dataNdx = offset
   val maxDataNdx = offset + len
   val lines = (len + 16) / 16
   for (i in 0 until lines) {
      ascii.append(" |")
      formatter.format("   %08x  ", displayOffset + i * 16)

      for (j in 0..15) {
         if (dataNdx < maxDataNdx) {
            val b = data[dataNdx++]
            formatter.format("%02x ", b)
            ascii.append(if (b in VISIBLE_ASCII) b.toInt().toChar() else ' ')
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
