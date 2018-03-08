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

import java.io.IOError
import java.nio.ByteBuffer
import java.io.EOFException
import java.nio.charset.StandardCharsets

import scala.collection.mutable.HashMap

import org.apache.hadoop.fs.Path
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.conf.Configuration

import scala.util.{Try, Success, Failure}

/**
  * This is the beginning of a FITS library in Scala.
  * You will find a large number of methodes to manipulate Binary Table HDUs.
  * There is no support for image HDU for the moment.
  */
object FitsLib {

  // Define some FITS standards.

  // Standard size of a header (bytes)
  val HEADER_SIZE_BYTES = 2880

  // Size of one row in header (bytes)
  val FITS_HEADER_CARD_SIZE = 80

  // Size of KEYWORD (KEYS) in FITS (bytes)
  val MAX_KEYWORD_LENGTH = 8

  /**
    * Main class to handle a HDU of a fits file. Main features are
    *   - Retrieving a HDU (block) of data
    *   - Split the HDU into a header and a data block
    *   - Get informations on data from the header (column name, element types, ...)
    *
    *
    * @param hdfsPath : (Path)
    *   Hadoop path containing informations on the file to read.
    * @param conf : (Configuration)
    *   Hadoop configuration containing informations on the run.
    * @param hduIndex : (Int)
    *   Index of the HDU to read (zero-based).
    *
    */
  class FitsBlock(hdfsPath : Path, conf : Configuration, hduIndex : Int) {

    // Open the data
    val fs = hdfsPath.getFileSystem(conf)
    val data = fs.open(hdfsPath)

    // Check that the HDU asked is below the max HDU index.
    val numberOfHdus = getNHDU
    val isHDUBelowMax = hduIndex < numberOfHdus
    isHDUBelowMax match {
      case true => isHDUBelowMax
      case false => throw new AssertionError(s"""
        HDU number $hduIndex does not exist!
        """)
    }

    // Compute the bound and initialise the cursor
    // indices (headerStart, dataStart, dataStop) in bytes.
    val blockBoundaries = BlockBoundaries

    val empty_hdu = if (blockBoundaries._2 == blockBoundaries._3) {
      true
    } else false

    // Get the header and set the cursor to its start.
    val blockHeader = readHeader
    resetCursorAtHeader

    // Get informations on element types and number of columns.
    val rowTypes = if (empty_hdu) {
      List[String]()
    } else getColTypes(blockHeader)
    val ncols = rowTypes.size

    // splitLocations is an array containing the location of elements
    // (byte index) in a row. Example if we have a row with [20A, E, E], one
    // will have splitLocations = [0, 20, 24, 28] that is a string on 20 Bytes,
    // followed by 2 floats on 4 bytes each.
    val splitLocations = if (empty_hdu) {
      List[Int]()
    } else {
      (0 :: rowSplitLocations(0)).scan(0)(_ +_).tail
    }

    // Size in Bytes of one row
    val rowSizeLong = if (empty_hdu) {
      0
    } else {
      getSizeRowBytes(blockHeader)
    }

    /**
      * Return the indices of the first and last bytes of the HDU:
      * hdu_start=header_start, data_start, data_stop, hdu_stop
      *
      * @return (Long, Long, Long, Long), the split of the HDU.
      *
      */
    def BlockBoundaries : (Long, Long, Long, Long) = {

      // Initialise the cursor position at the beginning of the file
      data.seek(0)
      var hdu_tmp = 0

      // Initialise the boundaries
      var header_start : Long = 0
      var data_start : Long = 0
      var data_stop : Long = 0
      var block_stop : Long = 0

      // Loop over HDUs, and stop at the desired one.
      do {
        // Initialise the offset to the header position
        header_start = data.getPos

        // add the header size (and move after it)
        val localHeader = readHeader

        // Data block starts after the header
        data_start = data.getPos

        // Size of the data block in Bytes.
        // Skip Data if None (typically HDU=0)
        val datalen = Try {
          getNRows(localHeader) * getSizeRowBytes(localHeader)
        }.getOrElse(0L)

        // Where the actual data stopped
        data_stop = data.getPos + datalen

        // Store the final offset
        // FITS is made of blocks of size 2880 bytes, so we might need to
        // pad to jump from the end of the data to the next header.
        block_stop = if ((data.getPos + datalen) % HEADER_SIZE_BYTES == 0) {
          data_stop
        } else {
          data_stop + HEADER_SIZE_BYTES -  (data_stop) % HEADER_SIZE_BYTES
        }

        // Move to the another HDU if needed
        hdu_tmp = hdu_tmp + 1
        data.seek(block_stop)

      } while (hdu_tmp < hduIndex + 1 )

      // Reposition the cursor at the beginning of the block
      data.seek(header_start)

      // Return boundaries (included):
      // hdu_start=header_start, data_start, data_stop, hdu_stop
      (header_start, data_start, data_stop, block_stop)
    }

    /**
      * Return the number of HDUs in the file.
      *
      * @return (Int) the number of HDU.
      *
      */
    def getNHDU : Int = {

      // Initialise the file
      data.seek(0)
      var hdu_tmp = 0

      // Initialise the boundaries
      var data_stop : Long = 0
      var e : Boolean = true

      // Loop over all HDU, and exit.
      do {

        // Get the header (and move after it)
        // Could be better handled with Try/Success/Failure.
        val localHeader = Try{readHeader}.getOrElse(Array[String]())

        // If the header cannot be read,
        e = if (localHeader.size == 0) {
          false
        } else true

        // Size of the data block in Bytes.
        // Skip Data if None (typically HDU=0)
        val datalen = Try {
          getNRows(localHeader) * getSizeRowBytes(localHeader)
        }.getOrElse(0L)

        // Store the final offset
        // FITS is made of blocks of size 2880 bytes, so we might need to
        // pad to jump from the end of the data to the next header.
        data_stop = if ((data.getPos + datalen) % HEADER_SIZE_BYTES == 0) {
          data.getPos + datalen
        } else {
          data.getPos + datalen + HEADER_SIZE_BYTES -  (data.getPos + datalen) % HEADER_SIZE_BYTES
        }

        // Move to the another HDU if needed
        hdu_tmp = hdu_tmp + 1
        data.seek(data_stop)

      } while (e)

      // Return the number of HDU.
      hdu_tmp - 1
    }

    /**
      * Place the cursor at the beginning of the header of the block
      *
      */
    def resetCursorAtHeader = {
      // Place the cursor at the beginning of the block
      data.seek(blockBoundaries._1)
    }

    /**
      * Place the cursor at the beginning of the data of the block
      *
      */
    def resetCursorAtData = {
      // Place the cursor at the beginning of the block
      data.seek(blockBoundaries._2)
    }

    /**
      * Set the cursor at the `position` (byte index, Long).
      *
      * @param position : (Long)
      *   The byte index to seek in the file.
      *
      */
    def setCursor(position : Long) = {
      data.seek(position)
    }

    /**
      * Read a header at a given position
      *
      * @param position : (Long)
      *   The byte index to seek in the file. Need to correspond to a valid
      *   header position. Use in combination with BlockBoundaries._1
      *   for example.
      * @return (Array[String) the header is an array of Strings, each String
      *   being one line of the header.
      */
    def readHeader(position : Long) : Array[String] = {
      setCursor(position)
      readHeader
    }

    /**
      * Read the header of a HDU. The cursor needs to be at the start of
      * the header. We assume that each header row has a standard
      * size of 80 Bytes, and the total size of the header is 2880 Bytes.
      *
      * @return (Array[String) the header is an array of Strings, each String
      *   being one line of the header.
      */
    def readHeader : Array[String] = {

      // Initialise a line of the header
      var buffer = new Array[Byte](FITS_HEADER_CARD_SIZE)

      var len = 0
      var stop = 0
      var pos = 0
      var stopline = 0
      var header = new Array[String](HEADER_SIZE_BYTES / FITS_HEADER_CARD_SIZE)

      // Loop until the end of the header.
      // TODO: what if the header has an non-standard size?
      do {
        len = data.read(buffer, 0, FITS_HEADER_CARD_SIZE)
        if (len == 0) {
          throw new EOFException("nothing to read left")
        }
        stop += len

        // Bytes to Strings
        header(pos) = new String(buffer, StandardCharsets.UTF_8)

        // Remove blanck lines at the end
        stopline = if (header(pos).trim() != "") {
          stopline + 1
        } else stopline

        // Increment the line
        pos += 1
      } while (stop < HEADER_SIZE_BYTES)

      // Return the header
      header.slice(0, stopline)
    }

    /**
      * Convert binary row into row. You need to have the cursor at the
      * beginning of a row. Example
      * {{{
      * // Set the cursor at the beginning of the data block
      * setCursor(BlockBoundaries._2)
      * // Initialise your binary row
      * val buffer = Array[Byte](size_of_one_row_in_bytes)
      * // Read the first binary row into buffer
      * data.read(buffer, 0, size_of_one_row_in_bytes)
      * // Convert buffer
      * val myrow = readLineFromBuffer(buffer)
      * }}}
      *
      * @param buf : (Array[Byte])
      *   Row of byte read from the data block.
      * @param col : (Int=0)
      *   Index of the column (used for the recursion).
      * @return (List[_]) The row as list of elements (float, int, string, etc.)
      *   as given by the header.
      *
      */
    def readLineFromBuffer(buf : Array[Byte], col : Int = 0): List[_] = {

      if (col == ncols) {
        Nil
      } else {
        getElementFromBuffer(buf.slice(splitLocations(col), splitLocations(col+1)), rowTypes(col)) :: readLineFromBuffer(buf, col + 1)
      }
    }

    /**
      * Companion to readLineFromBuffer. Convert one array of bytes
      * corresponding to one element of the table into its primitive type.
      *
      * @param subbuf : (Array[Byte])
      *   Array of byte describing one element of the table.
      * @param fitstype : (String)
      *   The type of this table element according to the header.
      * @return the table element converted from binary.
      *
      */
    def getElementFromBuffer(subbuf : Array[Byte], fitstype : String) : Any = {
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
              Cannot infer size of type $fitstype from the header!
              See com.sparkfits.FitsLib.getElementFromBuffer
              """)
          0
        }
      }
    }

    def readColsFromBuffer(cols: Array[Byte], index: Int=0): List[List[Any]] = {

      if (index == ncols) {
        Nil
      } else {
        // Need to know the type of the cols
        val bufferSize = splitLocations(index+1) - splitLocations(index)
        // println(bufferSize, rowSizeLong.toInt)

        // Take a buffer size every rowsizeth position.
        val colByteIterator = cols.slice(
          splitLocations(index), cols.size).sliding(bufferSize, rowSizeLong.toInt)
        val fitstype = rowTypes(index)
        fitstype match {
          case x if fitstype.contains("I") => readColShort(colByteIterator) :: readColsFromBuffer(cols, index + 1)
          case x if fitstype.contains("J") => readColInt(colByteIterator) :: readColsFromBuffer(cols, index + 1)
          case x if fitstype.contains("K") => readColLong(colByteIterator) :: readColsFromBuffer(cols, index + 1)
          case x if fitstype.contains("E") => readColFloat(colByteIterator) :: readColsFromBuffer(cols, index + 1)
          case x if fitstype.contains("D") => readColDouble(colByteIterator) :: readColsFromBuffer(cols, index + 1)
          case x if fitstype.contains("A") => readColChar(colByteIterator) :: readColsFromBuffer(cols, index + 1)
          case x if fitstype.contains("L") => readColBool(colByteIterator) :: readColsFromBuffer(cols, index + 1)
          case _ => {
            println(s"""
                Cannot infer size of type $fitstype from the header!
                See com.sparkfits.FitsLib.readColsFromBuffer
                """)
            List[Int]() :: readColsFromBuffer(cols, index + 1)
          }
        }
      }
    }

    /**
      * Read a portion of a column containing Floats.
      */
    def readColFloat(buffer: Iterator[Array[Byte]]) : List[Float] = {

      // Number of row
      // val nrow = buffer.size

      val col = buffer.map(x => ByteBuffer.wrap(x, 0, 4).getFloat())
      // val col = for {
      //   i <- 0 to nrow - 1
      // } yield(ByteBuffer.wrap(buffer.next(), 0, 4).getFloat())
      col.toList
    }

    /**
      * Read a portion of a column containing Floats.
      */
    def readColBool(buffer: Iterator[Array[Byte]]) : List[Boolean] = {

      // Number of row
      // val nrow = buffer.size

      val col = buffer.map(x => x(0).toChar == 'T')
      // val col = for {
      //   i <- 0 to nrow - 1
      // } yield(buffer.next()(0).toChar == 'T')
      col.toList
    }

    /**
      * Read a portion of a column containing Ints.
      */
    def readColInt(buffer: Iterator[Array[Byte]]) : List[Int] = {

      // Number of row
      // val nrow = buffer.size

      val col = buffer.map(x => ByteBuffer.wrap(x, 0, 4).getInt())
      // val col = for {
      //   i <- 0 to nrow - 1
      // } yield(ByteBuffer.wrap(buffer.next(), 0, 4).getInt())
      col.toList
    }

    /**
      * Read a portion of a column containing Ints.
      */
    def readColShort(buffer: Iterator[Array[Byte]]) : List[Short] = {

      // Number of row
      // val nrow = buffer.size

      val col = buffer.map(x => ByteBuffer.wrap(x, 0, 2).getShort())
      // val col = for {
      //   i <- 0 to nrow - 1
      // } yield(ByteBuffer.wrap(buffer.next(), 0, 2).getShort())
      col.toList
    }

    /**
      * Read a portion of a column containing Ints.
      */
    def readColLong(buffer: Iterator[Array[Byte]]) : List[Long] = {

      // Number of row
      // val nrow = buffer.size

      val col = buffer.map(x => ByteBuffer.wrap(x, 0, 8).getLong())
      // val col = for {
      //   i <- 0 to nrow - 1
      // } yield(ByteBuffer.wrap(buffer.next(), 0, 8).getLong())
      col.toList
    }

    /**
      * Read a portion of a column containing Ints.
      */
    def readColDouble(buffer: Iterator[Array[Byte]]) : List[Double] = {

      // Number of row
      // val nrow = buffer.size

      val col = buffer.map(x => ByteBuffer.wrap(x, 0, 8).getDouble())
      // val col = for {
      //   i <- 0 to nrow - 1
      // } yield(ByteBuffer.wrap(buffer.next(), 0, 8).getDouble())
      col.toList
    }

    /**
      * Read a portion of a column containing Ints.
      */
    def readColChar(buffer: Iterator[Array[Byte]]) : List[String] = {

      // Number of row
      // val nrow = buffer.size

      val col = buffer.map(x => new String(x, StandardCharsets.UTF_8).trim())
      // val col = for {
      //   i <- 0 to nrow - 1
      // } yield(new String(buffer.next(), StandardCharsets.UTF_8).trim())
      col.toList
    }

    /**
      * Return the types of elements for each column as a list.
      *
      * @param col : (Int)
      *   Column index used for the recursion.
      * @return (List[String]), list with the types of elements for each column
      *   as given by the header.
      *
      */
    def getColTypes(header : Array[String], col : Int = 0): List[String] = {
      // Get the names of the Columns
      val headerNames = getHeaderNames(header)

      // Get the number of Columns by recursion
      val ncols = getNCols(header)
      if (col == ncols) {
        Nil
      } else {
        headerNames("TFORM" + (col + 1).toString) :: getColTypes(header, col + 1)
      }
    }

    /**
      * Return the KEYWORDS of the header.
      *
      * @return (Array[String]), array with the KEYWORDS of the HDU header.
      *
      */
    def getHeaderKeywords(header : Array[String]) : Array[String] = {
      // Get the KEYWORDS
      val keywords = new Array[String](header.size)

      // Loop over KEYWORDS
      for (i <- 0 to header.size - 1) {
        val line = header(i)
        // Get the keyword
        keywords(i) = line.substring(0, MAX_KEYWORD_LENGTH).trim()
      }
      keywords
    }

    /**
      * Return the (KEYWORDS, VALUES) of the header
      *
      * @return (HashMap[String, Int]), map array with (keys, values_as_int).
      *
      */
    def getHeaderValues(header : Array[String]) : HashMap[String, Int] = {

      // Initialise our map
      val headerMap = new HashMap[String, Int]

      // Get the KEYWORDS of the Header
      val keys = getHeaderKeywords(header)

      // Loop over rows
      for (i <- 0 to header.size - 1) {

        // One row
        val row = header(i)

        // Split at the comment
        val v = row.split("/")(0)

        // Init
        var v_tmp = ""
        var offset = 0
        var letter : Char = 'a'

        // recursion to get the value. Reverse order!
        // 29. WTF???
        do {
          letter = v(29 - offset)
          v_tmp = v_tmp + letter.toString
          offset += 1
        } while (letter != ' ')

        // Reverse our result, and look for Int value.
        // Could be better... Especially if we have something else than Int?
        v_tmp = v_tmp.trim().reverse
        headerMap += (keys(i) -> Try{v_tmp.toInt}.getOrElse(0))
      }
      // Return the map(KEYWORDS -> VALUES)
      headerMap
    }

    /**
      * Get the names of the header.
      * We assume that the names are inside quotes 'my_name'.
      *
      * @param header : (Array[String])
      *   The header of the HDU.
      * @return (HashMap[String, String]), a map of keyword/name.
      *
      */
    def getHeaderNames(header : Array[String]) : HashMap[String, String] = {

      // Initialise the map
      val headerMap = new HashMap[String, String]

      // Get the KEYWORDS
      val keys = getHeaderKeywords(header)
      for (i <- 0 to header.size - 1) {

        // Take one row and make it an iterator of Char
        // from the end of the KEYWORD.
        val row = header(i)
        val it = row.substring(MAX_KEYWORD_LENGTH, FITS_HEADER_CARD_SIZE).iterator

        var name_tmp = ""
        var isName = false

        // Loop over the Chars of the row
        do {
          val nextChar = it.next()

          // Trigger/shut name completion
          if (nextChar == ''') {
            isName = !isName
          }

          // Add what is inside the quotes (left quote included)
          if (isName) {
            name_tmp = name_tmp + nextChar
          }
        } while (it.hasNext)

        // Try to see if there is something inside quotes
        // Return empty String otherwise.
        val name = Try{name_tmp.substring(1, name_tmp.length).trim()}.getOrElse("")

        // Update the map
        headerMap += (keys(i) -> name)
      }

      // Return the map
      headerMap
    }

    /**
      * Get the comments of the header.
      * We assume the comments are written after a backslash (\).
      *
      * @param header : (Array[String])
      *   The header of the HDU.
      * @return (HashMap[String, String]), a map of keyword/comment.
      *
      */
    def getHeaderComments(header : Array[String]) : HashMap[String, String] = {

      // Init
      val headerMap = new HashMap[String, String]

      // Get the KEYWORDS
      val keys = getHeaderKeywords(header)

      // Loop over header row
      for (i <- 0 to header.size - 1) {
        // One row
        val row = header(i)

        // comments are written after a backslash (\).
        // If None, return empty String.
        val comments = Try{row.split("/")(1).trim()}.getOrElse("")
        headerMap += (keys(i) -> comments)
      }

      // Return the Map.
      headerMap
    }

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
    def getNRows(header : Array[String]) : Long = {
      val values = getHeaderValues(header)
      values("NAXIS2")
    }

    /**
      * Get the number of column of a HDU.
      * We rely on what's written in the header, meaning
      * here we do not access the data directly.
      *
      * @param header : (Array[String])
      *   The header of the HDU.
      * @return (Long), the number of rows as written in KEYWORD=TFIELDS.
      *
      */
    def getNCols(header : Array[String]) : Long = {
      val values = getHeaderValues(header)
      values("TFIELDS")
    }

    /**
      * Get the size (bytes) of each row of a HDU.
      * We rely on what's written in the header, meaning
      * here we do not access the data directly.
      *
      * @param header : (Array[String])
      *   The header of the HDU.
      * @return (Long), the number of rows as written in KEYWORD=NAXIS1.
      *
      */
    def getSizeRowBytes(header : Array[String]) : Long = {
      val values = getHeaderValues(header)
      values("NAXIS1")
    }

    /**
      * Get the name of a column with index `colIndex` of a HDU.
      *
      * @param header : (Array[String])
      *   The header of the HDU.
      * @param colIndex : (Int)
      *   Index (zero-based) of a column.
      * @return (String), the name of the column.
      *
      */
    def getColumnName(header : Array[String], colIndex : Int) : String = {
      // Grab the header names as map(keywords/names)
      val names = getHeaderNames(header)
      // Zero-based index
      names("TTYPE" + (colIndex + 1).toString)
    }

    /**
      * Get the type of the elements of a column with index `colIndex` of a HDU.
      *
      * @param header : (Array[String])
      *   The header of the HDU.
      * @param colIndex : (Int)
      *   Index (zero-based) of a column.
      * @return (String), the type (FITS convention) of the elements of the column.
      *
      */
    def getColumnType(header : Array[String], colIndex : Int) : String = {
      // Grab the header names as map(keywords/names)
      val names = getHeaderNames(header)
      // Zero-based index
      names("TFORM" + (colIndex + 1).toString)
    }

    /**
      * Description of a row in terms of bytes indices.
      * rowSplitLocations returns an array containing the position of elements
      * (byte index) in a row. Example if we have a row with [20A, E, E], one
      * will have rowSplitLocations -> [0, 20, 24, 28] that is a string
      * on 20 Bytes, followed by 2 floats on 4 bytes each.
      *
      * @param col : (Int)
      *   Column position used for the recursion. Should be left at 0.
      * @return (List[Int]), the position of elements (byte index) in a row.
      *
      */
    def rowSplitLocations(col : Int = 0) : List[Int] = {
      if (col == ncols) {
        Nil
      } else {
        getSplitLocation(rowTypes(col)) :: rowSplitLocations(col + 1)
      }
    }

    /**
      * Companion routine to rowSplitLocations. Returns the size of a primitive
      * according to its type from the FITS header.
      *
      * @param fitstype : (String)
      *   Element type according to FITS standards (I, J, K, E, D, L, A, etc)
      * @return (Int), the size (bytes) of the element.
      *
      */
    def getSplitLocation(fitstype : String) : Int = {
      fitstype match {
        case x if fitstype.contains("I") => 2
        case x if fitstype.contains("J") => 4
        case x if fitstype.contains("K") => 8
        case x if fitstype.contains("E") => 4
        case x if fitstype.contains("D") => 8
        case x if fitstype.contains("L") => 1
        case x if fitstype.endsWith("A") => {
          // Example 20A means string on 20 bytes
          x.slice(0, x.length - 1).toInt
        }
        case _ => {
          println(s"""
              Cannot infer size of type $fitstype from the header!
              See com.sparkfits.FitsLib.getSplitLocation
              """)
          0
        }
      }
    }
  }
}
