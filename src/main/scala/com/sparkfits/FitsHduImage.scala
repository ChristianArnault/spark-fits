/*
 * Copyright 2018 Julien Peloton
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sparkfits

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.apache.spark.sql.types._

import com.sparkfits.FitsHdu._
import com.sparkfits.FitsSchema.ReadMyType

/**
  * Contain class and methods to manipulate Image HDU.
  */
object FitsHduImage {
  case class ImageHDU(header : Array[String]) extends HDU {

    // 8 bits in one byte
    val BYTE_SIZE = 8

    // Initialise the key/value from the header.
    val keyValues = FitsLib.parseHeader(header)

    // Compute the dimension of the image
    val elementSize = (keyValues("BITPIX").toInt) / BYTE_SIZE
    val dimensions = keyValues("NAXIS").toInt

    val axisBuilder = Array.newBuilder[Long]
    for (d <- 1 to dimensions){
      axisBuilder += keyValues("NAXIS" + d.toString).toLong
    }
    val axis = axisBuilder.result

    // Initialise type and byte size of image elements.
    val elementType = getColTypes(keyValues)

    /** Image HDU are implemented */
    override def implemented: Boolean = {true}

    /**
      * Get the number of row of a HDU.
      * We rely on what's written in the header, meaning
      * here we do not access the data directly.
      *
      * @param header : (Array[String])
      *   The header of the HDU.
      * @return (Long), the number of rows as written in KEYWORD=NAXIS2.
      *
      */
    override def getNRows(keyValues: Map[String, String]) : Long = {
      val totalBytes = axis.reduce(_ * _) * elementSize
      val rowBytes = getSizeRowBytes(keyValues)

      val result = if (totalBytes % rowBytes == 0) {
        (totalBytes / rowBytes / elementSize).toLong
      }
      else {
        ((totalBytes / rowBytes / elementSize) + 1).toLong
      }

      result
    }

    override def getSizeRowBytes(keyValues: Map[String, String]) : Int = {
      // // println(s"FitsImageLib.ImageHDU.getSizeRowBytes> ")
      var size = (elementSize * axis(0)).toInt
      // Try and get the integer division factor until size becomes lower than 1024
      var factor = 2
      do {
        if (size % factor == 0) {
          size /= factor
        }
        else {
          factor += 1
        }
      } while (size > 1024)
      println(size)
      size
    }

    override def getNCols(keyValues : Map[String, String]) : Long = {
      1L
    }

    /**
      * Return the type of image elements.
      *
      * @param keyValues : (Map[String, String])
      *   Key/Value pairs from the header (see parseHeader)
      * @return (List[String]), list of one element containing the type.
      *
      */
    override def getColTypes(keyValues : Map[String, String]): List[String] = {

      // Weird, image header does not have information on the type??
      // Just the number of bits is written...
      // So the following is just a guess, nothing serious
      val bitpix = keyValues("BITPIX").toInt / BYTE_SIZE
      bitpix match {
        case 2 => List("I")
        case 4 => List("E")
        case 8 => List("D")
      }
    }

    /**
      *
      * Build a list of one StructField from header information.
      * The list of StructField is then used to build the DataFrame schema.
      *
      * @return (List[StructField]) List of StructField with column name = Image,
      *   data type, and whether the data is nullable.
      *
      */
    override def listOfStruct : List[StructField] = {
      // Get the list of StructField.
      val lStruct = List.newBuilder[StructField]
      // lStruct += StructField("Image", ArrayType(ByteType, true))
      val tmp = ReadMyType("Image", elementType(0), true)
      lStruct += tmp.copy(tmp.name, ArrayType(tmp.dataType))
      println(lStruct.result)
      lStruct.result
    }

    /**
      *
      */
    override def getRow(buf: Array[Byte]): List[Any] = {
      val nelements_per_row = buf.size / elementSize
      val row = List.newBuilder[Any]
      for (pos <- 0 to nelements_per_row - 1) {
        row += getElementFromBuffer(
          buf.slice(pos * elementSize, (pos+1)*elementSize), elementType(0))
      }
      println(nelements_per_row, row.result)
      row.result
    }

    override def getElementFromBuffer(subbuf : Array[Byte], fitstype : String) : Any = {
      fitstype match {
        // 16-bit Integer
        case x if fitstype.contains("I") => {
          ByteBuffer.wrap(subbuf, 0, 2).getShort()
        }
        // 32-bit Integer
        case x if fitstype.contains("J") => {
          ByteBuffer.wrap(subbuf, 0, 4).getInt()
        }
        // 64-bit Integer
        case x if fitstype.contains("K") => {
          ByteBuffer.wrap(subbuf, 0, 8).getLong()
        }
        // Single precision floating-point
        case x if fitstype.contains("E") => {
          ByteBuffer.wrap(subbuf, 0, 4).getFloat()
        }
        // Double precision floating-point
        case x if fitstype.contains("D") => {
          ByteBuffer.wrap(subbuf, 0, 8).getDouble()
        }
        // Boolean
        case x if fitstype.contains("L") => {
          // 1 Byte containing the ASCII char T(rue) or F(alse).
          subbuf(0).toChar == 'T'
        }
        // Chain of characters
        case x if fitstype.endsWith("A") => {
          // Example 20A means string on 20 bytes
          new String(subbuf, StandardCharsets.UTF_8).trim()
        }
        case _ => {
          println(s"""
            FitsLib.getElementFromBuffer> Cannot infer size of type
            $fitstype from the header! See getElementFromBuffer
              """)
          0
        }
      }
    }
  }
}
