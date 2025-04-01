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
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types._
import org.scalatest.funsuite.AnyFunSuite

import java.util
import scala.jdk.CollectionConverters._

/** Loading data from named table
  */
object TableReadSuite {
  val expectedSchema = StructType(
    List(
      StructField("City", StringType, true),
      StructField("Latitude", DoubleType, true),
      StructField("Longitude", DoubleType, true),
      StructField("Population", IntegerType, true)
    )
  )

  val expectedBigCitiesData: util.List[Row] = List(
    Row("Shanghai, China", 31.23d, 121.5d, 24256800),
    Row("Karachi, Pakistan", 24.86d, 67.01d, 23500000),
    Row("Beijing, China", 39.93d, 116.4d, 21516000),
    Row("São Paulo, Brazil", -23.53d, -46.63d, 21292900),
    Row("Delhi, India", 28.67d, 77.21d, 16788000),
    Row("Lagos, Nigeria", 6.45d, 3.47d, 16060000),
    Row("Istanbul, Turkey", 41.1d, 29d, 14657000),
    Row("Tokyo, Japan", 35.67d, 139.8d, 13298000),
    Row("Mumbai, India", 18.96d, 72.82d, 12478000),
    Row("Moscow, Russia", 55.75d, 37.62d, 12198000),
    Row("Guangzhou, China", 23.12d, 113.3d, 12081000),
    Row("Shenzhen, China", 22.55d, 114.1d, 10780000),
    Row("Total", 25.3966666666667d, 70.4666666666667d, 198905700)
  ).asJava

  val expectedSmallCitiesData: util.List[Row] = List(
    Row("Pyongyang, North Korea", 39.02d, 125.74d, 2581000),
    Row("Buenos Aires, Argentina", -34.6d, -58.38d, 2890000),
    Row("Seattle, Washington, USA", 47.61d, -122.33d, 609000),
    Row("Toronto, Canada", 43.7d, -79.4d, 2615000),
    Row("Auckland, New Zealand", -36.84d, 174.74d, 1454000),
    Row("Miami, Florida, USA", 25.78d, -80.21d, 400000),
    Row("Havana, Cuba", 23.13d, -82.38d, 2106000),
    Row("Fairbanks, Alaska, USA", 64.84d, -147.72d, 32000),
    Row("Longyearbyen, Svalbard", 78.22d, 15.55d, 2600),
    Row("Johannesburg, South Africa", -26.2d, 28d, 957000),
    Row("Cancun, Mexico", 21.16d, -86.85d, 722800),
    Row("Oahu, Hawaii, USA", 21.47d, -157.98d, 953000),
    Row("Total", 22.2741666666667d, -39.2683333333333d, 15322400)
  ).asJava

}

class TableReadSuite extends AnyFunSuite with DataFrameSuiteBase with ExcelTestingUtilities {
  import TableReadSuite._

  test("named-table SmallCity with testing data from Apache POI upstream tests") {
    val df = readFromResources(
      spark,
      path = "apache_poi/DataTableCities.xlsx",
      options = Map("dataAddress" -> "SmallCity[All]", "inferSchema" -> true)
    )
    val expected = spark.createDataFrame(expectedSmallCitiesData, expectedSchema)
    assertDataFrameApproximateEquals(expected, df, 0.1e-3)
  }

  test("named-table BigCity with testing data from Apache POI upstream tests") {
    val df = readFromResources(
      spark,
      path = "apache_poi/DataTableCities.xlsx",
      options = Map("dataAddress" -> "BigCity[All]", "inferSchema" -> true)
    )
    val expected = spark.createDataFrame(expectedBigCitiesData, expectedSchema)
    assertDataFrameApproximateEquals(expected, df, 0.1e-3)
  }

  test("read sheet names consisting of digits only (do not interpret given sheet name as number)") {
    val df = readFromResources(
      spark,
      path = "issue_942_sheetname_digits.xlsx",
      options = Map("dataAddress" -> "'001'!A1", "inferSchema" -> true, "header" -> true, "maxRowsInMemory" -> 1000)
    )

    assert(df.schema.fields.length == 5) // sheet 001 has 5 columns, sheet 002 has 3 columns
    assert(df.count() == 1) // sheet 001 has 1 row
    assert(df.first().getString(0) == "A") // sheet 001 contains "A" as first cell value (001 has "WRONG")
  }

  for (useStreamingReader <- Seq(true, false)) {
    test(
      s"check handling of faulty dimension tags using streaming reader == $useStreamingReader (keepUndefinedRows=false)"
    ) {
      val df = readFromResources(
        spark,
        path = "issue_944_faulty_dimension.xlsx",
        options = Map(
          "dataAddress" -> "'faulty_dim'!A1",
          "inferSchema" -> true,
          "header" -> true,
          "keepUndefinedRows" -> false
        ) ++ (if (useStreamingReader) Map("maxRowsInMemory" -> "1000") else Map.empty)
      )
      assert(df.schema.fields.length == 5) // sheet has 5 columns
      assert(df.count() == 2) // sheet has 2 defined rows (row 2 and 4)
      assert(df.first().getString(0) == "A") // sheet  contains "A" as first cell value in first row
    }
  }

  test("check handling of faulty dimension tags (keepUndefinedRows=true)") {
    // note that keepUndefinedRows=true does not work with streaming reader
    val df = readFromResources(
      spark,
      path = "issue_944_faulty_dimension.xlsx",
      options =
        Map("dataAddress" -> "'faulty_dim'!A1", "inferSchema" -> true, "header" -> true, "keepUndefinedRows" -> true)
    )
    assert(df.schema.fields.length == 5) // sheet 001 has 5 columns, sheet 002 has 3 columns
    assert(df.count() == 3) // sheet  has row 2 and 4 defined, while row 3 is not defined => 3 rows in total (2,3,4)
    assert(df.first().getString(0) == "A") // sheet 001 contains "A" as first cell value
  }

  test("read multiple sheets at once (filename as regex)") {
    val df = readFromResources(
      spark,
      path = "read_multiple_sheets_at_once.xlsx",
      options =
        Map("sheetNameIsRegex" -> true, "dataAddress" -> "'sheet_[0-9]'!A1", "inferSchema" -> true, "header" -> true)
    )

    assert(df.schema.fields.length == 2)
    assert(df.count() == 5) // sheet_1 has 3 rows, sheet_2 has 2 rows => 5
    assert(df.filter(col("col_name") === "sheet_1").count() == 3)
    assert(df.filter(col("col_name") === "sheet_2").count() == 2)
  }

  test("read multiple sheets at once (filename as regex, excel without header)") {
    val expectedSchema =
      StructType(List(StructField("col_name", StringType, true), StructField("col_id", StringType, true)))

    val df = readFromResources(
      spark,
      path = "read_multiple_sheets_at_once_noheader.xlsx",
      options =
        Map("sheetNameIsRegex" -> true, "dataAddress" -> "'sheet_[0-9]'!A1", "inferSchema" -> false, "header" -> false),
      schema = expectedSchema
    )
    assert(df.count() == 5) // sheet_1 has 3 rows, sheet_2 has 2 rows => 5
    assert(df.filter(col("col_name") === "sheet_1").count() == 3)
    assert(df.filter(col("col_name") === "sheet_2").count() == 2)
  }

}
