package edu.scholarchain.spark

import edu.scholarchain.core.*

class TemporalAnalysisSpec extends SparkTestBase:

  test("generateWindows produces correct non-overlapping windows") {
    val windows = TemporalAnalysisJob.generateWindows(2015, 2024, 5, 5)
    windows.length shouldBe 2
    windows.head.startYear shouldBe 2015
    windows.head.endYear shouldBe 2019
    windows.last.startYear shouldBe 2020
    windows.last.endYear shouldBe 2024
  }

  test("temporal snapshots reflect only windowed data") {
    val edgesRdd = sc.parallelize(TestFixtures.allEdges)
    val authorships = sc.parallelize(
      Seq(
        ("R1", "w1", 1, 2018),
        ("R2", "w1", 1, 2018),
        ("R1", "w3", 2, 2019),
        ("R2", "w3", 2, 2019),
        ("R3", "w3", 2, 2019),
        ("R2", "w4", 1, 2020),
        ("R4", "w4", 1, 2020),
        ("R4", "w5", 1, 2021),
        ("R5", "w5", 1, 2021)
      )
    )

    val windows = Seq(
      TemporalAnalysisJob.TimeWindow(2018, 2019),
      TemporalAnalysisJob.TimeWindow(2020, 2021)
    )

    val snapshots = TemporalAnalysisJob.compute(
      sc,
      edgesRdd,
      authorships,
      windows,
      "run",
      "1.0.0",
      "ts"
    )
    val collected = snapshots.collect()

    // In the 2018-2019 window, R4 and R5 should have 0 publications
    val r4Window1 = collected.find(s => s.researcherId == "R4" && s.windowStartYear == 2018)
    r4Window1.foreach { s =>
      s.publicationCount shouldBe 0
    }

    // In the 2020-2021 window, R1 should have 0 publications but may appear via edges
    val r1Window2 = collected.find(s => s.researcherId == "R1" && s.windowStartYear == 2020)
    r1Window2.foreach { s =>
      s.publicationCount shouldBe 0
    }
  }

  test("temporal analysis with empty edges produces empty results") {
    val emptyEdges       = sc.emptyRDD[CoauthorEdge]
    val emptyAuthorships = sc.emptyRDD[(String, String, Int, Int)]
    val windows          = Seq(TemporalAnalysisJob.TimeWindow(2020, 2025))

    val snapshots = TemporalAnalysisJob.compute(
      sc,
      emptyEdges,
      emptyAuthorships,
      windows,
      "run",
      "1.0.0",
      "ts"
    )
    snapshots.count() shouldBe 0
  }
