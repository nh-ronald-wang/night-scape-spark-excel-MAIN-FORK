/*
 * Copyright 2022 Martin Mauch (@nightscape)
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

package dev.mauch.spark.excel.v2

import dev.mauch.spark.DataFrameSuiteBase
import org.apache.spark.sql.Row
import org.apache.spark.sql.types._
import org.scalatest.funsuite.AnyFunSuite

import java.util
import scala.jdk.CollectionConverters._

object KeepUndefinedRowsSuite {

  /* Issue: https://github.dev/mauch/spark-excel/issues/285 */
  val expectedSchema_Issue285 = StructType(
    List(StructField("1", StringType, true), StructField("2", StringType, true), StructField("3", StringType, true))
  )

  /** No change to the spark-excel, Apache POI also produce same result with sheet.iterator
    *
    * Workaround: https://stackoverflow.com/questions/47790569/how-to-avoid-skipping-blank-rows-or-columns-in-apache-poi
    * Doc: http://poi.apache.org/components/spreadsheet/quick-guide.html#Iterator
    */
  val expectedData_Issue285: util.List[Row] = List(
    Row("File info", null, null),
    Row("Info", "Info", "Info"),
    Row("Metadata", null, null),
    Row(null, "1", "2"),
    Row("A", "1", "2"),
    Row("B", "5", "6"),
    Row("C", "9", "10"),
    Row("Metadata", null, null),
    Row(null, "1", "2"),
    Row("A", "1", "2"),
    Row("B", "4", "5"),
    Row("C", "7", "8")
  ).asJava

  /* With newly introduced keepUndefinedRows option */
  val expectedData_KeepUndefinedRows_Issue285: util.List[Row] = List(
    Row("File info", null, null),
    Row("Info", "Info", "Info"),
    Row(null, null, null),
    Row("Metadata", null, null),
    Row(null, null, null),
    Row(null, "1", "2"),
    Row("A", "1", "2"),
    Row("B", "5", "6"),
    Row("C", "9", "10"),
    Row(null, null, null),
    Row(null, null, null),
    Row("Metadata", null, null),
    Row(null, null, null),
    Row(null, "1", "2"),
    Row("A", "1", "2"),
    Row("B", "4", "5"),
    Row("C", "7", "8")
  ).asJava

  /** Issue: https://github.dev/mauch/spark-excel/issues/162 Spark-excel still infers to Double-Type, however, user can
    * provide custom scheme and Spark-excel should load to IntegerType or LongType accordingly
    */
  val userDefined_Issue162 = StructType(
    List(
      StructField("ID", IntegerType, true),
      StructField("address", StringType, true),
      StructField("Pin", IntegerType, true)
    )
  )

  val expectedData_Issue162: util.List[Row] =
    List(Row(123123, "Asdadsas, Xyxyxy, 123xyz", 123132), Row(123124, "Asdadsas1, Xyxyxy, 123xyz", 123133)).asJava

}

class KeepUndefinedRowsSuite extends AnyFunSuite with DataFrameSuiteBase with ExcelTestingUtilities {
  import KeepUndefinedRowsSuite._

  test("#285 undefined rows: no keep") {
    val df = readFromResources(
      spark,
      path = "issue_285_bryce21.xlsx",
      options = Map("header" -> false, "inferSchema" -> false, "keepUndefinedRows" -> false),
      schema = expectedSchema_Issue285
    )
    val expected = spark.createDataFrame(expectedData_Issue285, expectedSchema_Issue285)
    assertDataFrameEquals(expected, df)
  }

  test("#162 load integer values with user defined schema") {
    val df = readFromResources(
      spark,
      path = "issue_162_nihar_gharat.xlsx",
      options = Map("header" -> true),
      schema = userDefined_Issue162
    )
    val expected = spark.createDataFrame(expectedData_Issue162, userDefined_Issue162)
    assertDataFrameEquals(expected, df)
  }

  for (sheetName <- Seq("blank_row", "space_row")) {
    test(s"#965 handling of NULL/BLANK column values (streamingReader, keepUndefinedRows==false, sheet=$sheetName)") {
      val df = readFromResources(
        spark,
        path = "issue_965_blank_rows.xlsx",
        options = Map(
          "dataAddress" -> s"'${sheetName}'!A1",
          "inferSchema" -> true,
          "header" -> true,
          "maxRowsInMemory" -> "1000",
          "keepUndefinedRows" -> false
        )
      )
      assert(df.schema.fields.length == 5) // sheet 001 has 5 columns
      /*
        sheet "blank_row" has row 2 and 4 defined, while row 3 is not defined in excel xml and row 5 contains empty cells in excel xml
        => 2 rows in total (prior the fix row 5 was added as well)
        sheet "space_row" has row 2 and 4 defined with some values, row 3 contains just a whitespace in A3
        => 3 rows in total (just to test that a single whitespace is handled correctly)
       */
      if (sheetName == "blank_row") {
        assert(df.count() == 2)
      } else {
        assert(df.count() == 3)
      }
    }
  }

  for (keepUndefinedRows <- Seq(false, true)) {
    test(s"#965 handling of NULL/BLANK column values (NON-streaming-Reader, keepUndefinedRows==$keepUndefinedRows)") {
      val df = readFromResources(
        spark,
        path = "issue_965_blank_rows.xlsx",
        options = Map(
          "dataAddress" -> s"'blank_row'!A1",
          "inferSchema" -> true,
          "header" -> true,
          "keepUndefinedRows" -> keepUndefinedRows
        )
      )
      assert(df.schema.fields.length == 5) // sheet 001 has 5 columns
      /*
        sheet "blank_row" has row 2 and 4 defined, while row 3 is not defined in excel xml and row 5 contains empty cells in excel xml
       * keepUndefinedRows == true => 4 rows in total
       * keepUndefinedRows == false => 2 rows in total
       */
      if (keepUndefinedRows) {
        assert(df.count() == 4)
      } else {
        assert(df.count() == 2)
      }
    }
  }

}
