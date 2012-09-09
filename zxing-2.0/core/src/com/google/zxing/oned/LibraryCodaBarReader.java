/*
 * Copyright 2008 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.oned;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.BitArray;

import java.util.Map;

/**
 * <p>Decodes Codabar barcodes.</p>
 * <p>Adpating the CodabarReader.java by Bas Vijwinkel
 * <p>This is a barcode with the following attributes:
 *    <ul> start &amp; stop charcters A-D, these are ignored for check digit </ul>
 *    <ul> first character either a 2 or 3 (patron or item) </ul>
 *    <ul> next four characters are institution id (all digits)</ul>
 *    <ul> 8 digits </ul>
 *    <ul> a check digit </ul>
 *
 *   
 *
 * @author Jon Gorman
 * @author Bas Vijfwinkel
 */
public final class LibraryCodaBarReader extends OneDReader {

  private static final String ALPHABET_STRING = "0123456789-$:/.+ABCDTN";
  static final char[] ALPHABET = ALPHABET_STRING.toCharArray();

  /**
   * These represent the encodings of characters, as patterns of wide and narrow bars. The 7 least-significant bits of
   * each int correspond to the pattern of wide and narrow, with 1s representing "wide" and 0s representing narrow. NOTE
   * : c is equal to the  * pattern NOTE : d is equal to the e pattern
   */
  static final int[] CHARACTER_ENCODINGS = {
      0x003, 0x006, 0x009, 0x060, 0x012, 0x042, 0x021, 0x024, 0x030, 0x048, // 0-9
      0x00c, 0x018, 0x045, 0x051, 0x054, 0x015, 0x01A, 0x029, 0x00B, 0x00E, // -$:/.+ABCD
      0x01A, 0x029 //TN
  };

    
  // when dealing with the "Library Codabar"
  // it will be 14 digits
  //
  private static final int characterLength = 14; 
  
  // multiple start/end patterns
  // official start and end patterns
  private static final char[] STARTEND_ENCODING = {'E', '*', 'A', 'B', 'C', 'D', 'T', 'N'};
  // some codabar generator allow the codabar string to be closed by every character
  //private static final char[] STARTEND_ENCODING = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-', '$', ':', '/', '.', '+', 'A', 'B', 'C', 'D', 'T', 'N'};
  
  // some industries use a checksum standard but this is not part of the original codabar standard
  // for more information see : http://www.mecsw.com/specs/codabar.html

  @Override
  public Result decodeRow(int rowNumber, BitArray row, Map<DecodeHintType,?> hints)
      throws NotFoundException {
    int[] start = findAsteriskPattern(row);
    start[1] = 0; // BAS: settings this to 0 improves the recognition rate somehow?
    // Read off white space    
    int nextStart = row.getNextSet(start[1]);
    int end = row.getSize();

    StringBuilder result = new StringBuilder();
    int[] counters = new int[7];
    int lastStart;

    do {
      for (int i = 0; i < counters.length; i++) {
        counters[i] = 0;
      }
      recordPattern(row, nextStart, counters);

      char decodedChar = toNarrowWidePattern(counters);
      if (decodedChar == '!') {
        throw NotFoundException.getNotFoundInstance();
      }
      result.append(decodedChar);
      lastStart = nextStart;
      for (int counter : counters) {
        nextStart += counter;
      }

      // Read off white space
      nextStart = row.getNextSet(nextStart);
    } while (nextStart < end); // no fixed end pattern so keep on reading while data is available

    // Look for whitespace after pattern:
    int lastPatternSize = 0;
    for (int counter : counters) {
      lastPatternSize += counter;
    }

    int whiteSpaceAfterEnd = nextStart - lastStart - lastPatternSize;
    // If 50% of last pattern size, following last pattern, is not whitespace, fail
    // (but if it's whitespace to the very end of the image, that's OK)
    if (nextStart != end && (whiteSpaceAfterEnd / 2 < lastPatternSize)) {
      throw NotFoundException.getNotFoundInstance();
    }

    // valid result?
    if (result.length() < 2) {
      throw NotFoundException.getNotFoundInstance();
    }

    char startchar = result.charAt(0);
    if (!arrayContains(STARTEND_ENCODING, startchar)) {
      // invalid start character
      throw NotFoundException.getNotFoundInstance();
    }

    // find stop character
    for (int k = 1; k < result.length(); k++) {
      if (result.charAt(k) == startchar) {
        // found stop character -> discard rest of the string
        if (k + 1 != result.length()) {
          result.delete(k + 1, result.length() - 1);
          break;
        }
      }
    }

  // remove stop/start characters character and check if a string longer than 5 characters is contained
    if (result.length() != characterLength + 2) {
      // Almost surely a false positive ( start + stop + 14 characters)
      throw NotFoundException.getNotFoundInstance();
    }

    result.deleteCharAt(result.length() - 1);
    result.deleteCharAt(0);

    if (! validCodaBarCheckDigit( result ) ) {
      throw NotFoundException.getNotFoundInstance();
    }
    
    float left = (float) (start[1] + start[0]) / 2.0f;
    float right = (float) (nextStart + lastStart) / 2.0f;
    return new Result(
        result.toString(),
        null,
        new ResultPoint[]{
            new ResultPoint(left, (float) rowNumber),
            new ResultPoint(right, (float) rowNumber)},
        BarcodeFormat.CODABAR);
  }

  private static int[] findAsteriskPattern(BitArray row) throws NotFoundException {
    int width = row.getSize();
    int rowOffset = row.getNextSet(0);

    int counterPosition = 0;
    int[] counters = new int[7];
    int patternStart = rowOffset;
    boolean isWhite = false;
    int patternLength = counters.length;

    for (int i = rowOffset; i < width; i++) {
      if (row.get(i) ^ isWhite) {
        counters[counterPosition]++;
      } else {
        if (counterPosition == patternLength - 1) {
          try {
            if (arrayContains(STARTEND_ENCODING, toNarrowWidePattern(counters))) {
              // Look for whitespace before start pattern, >= 50% of width of start pattern
              if (row.isRange(Math.max(0, patternStart - (i - patternStart) / 2), patternStart, false)) {
                return new int[]{patternStart, i};
              }
            }
          } catch (IllegalArgumentException re) {
            // no match, continue
          }
          patternStart += counters[0] + counters[1];
          System.arraycopy(counters, 2, counters, 0, patternLength - 2);
          counters[patternLength - 2] = 0;
          counters[patternLength - 1] = 0;
          counterPosition--;
        } else {
          counterPosition++;
        }
        counters[counterPosition] = 1;
        isWhite ^= true; // isWhite = !isWhite;
      }
    }
    throw NotFoundException.getNotFoundInstance();
  }

  static boolean arrayContains(char[] array, char key) {
    if (array != null) {
      for (char c : array) {
        if (c == key) {
          return true;
        }
      }
    }
    return false;
  }

  private static char toNarrowWidePattern(int[] counters) {
    // BAS : I have changed the following part because some codabar images would fail with the original routine
    //        I took from the Code39Reader.java file
    // ----------- change start
    int numCounters = counters.length;
    int maxNarrowCounter = 0;

    int minCounter = Integer.MAX_VALUE;
    for (int i = 0; i < numCounters; i++) {
      if (counters[i] < minCounter) {
        minCounter = counters[i];
      }
      if (counters[i] > maxNarrowCounter) {
        maxNarrowCounter = counters[i];
      }
    }
    // ---------- change end


    do {
      int wideCounters = 0;
      int pattern = 0;
      for (int i = 0; i < numCounters; i++) {
        if (counters[i] > maxNarrowCounter) {
          pattern |= 1 << (numCounters - 1 - i);
          wideCounters++;
        }
      }

      if ((wideCounters == 2) || (wideCounters == 3)) {
        for (int i = 0; i < CHARACTER_ENCODINGS.length; i++) {
          if (CHARACTER_ENCODINGS[i] == pattern) {
            return ALPHABET[i];
          }
        }
      }
      maxNarrowCounter--;
    } while (maxNarrowCounter > minCounter);
    return '!';
  }

  // decided to make this public for easier
  // testing, want to make sure don't break this algorithm
  public static boolean validCodaBarCheckDigit(StringBuilder result) {
    
    // e - sum of evens
    // o - sum of (odds * 2 [- 9]) (-9 is only if odss* 2 is > 9)
    // r - remainder of (e + o) % 10
    // if (r == 0), check digit is 0, else check digit is 10 - r
      
      int even_total = 0 ;
      for(int evenpos = 1; evenpos < result.length() - 1; evenpos += 2) {
        int even = Integer.parseInt( result.substring( evenpos, evenpos+1 ) ) ;
        even_total += even ;
      }

      int odd_total = 0 ;
      for (int oddpos = 0; oddpos < result.length() - 1; oddpos += 2) {
        int odd = Integer.parseInt( result.substring( oddpos, oddpos+1 ) ) ;
          if(odd * 2 > 9) {
            odd_total += odd * 2 - 9 ;
          }
          else {
            odd_total += odd * 2 ;
          }
      }
      
      int remainder = (even_total + odd_total) % 10 ;
      int checkDigit ;
      if( remainder == 0) {
        checkDigit = 0 ;
      }
      else {
        checkDigit = 10 - remainder ;
      }
      if (Integer.parseInt( result.substring( result.length() - 1 ) ) == checkDigit) {
        return true ;
      }
      
      return false ;
  }
  
}
    

