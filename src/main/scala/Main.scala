import com.typesafe.config.ConfigFactory
import entity._
import io.{ParameterizedReader, Writer}
import mapper.DriveMapper
import reportGenerator.{BikeStatsReportGenerator, GeneralStatsReportGenerator, UsageStatsReportGenerator}
import repository.{DriveRepository, StationRepository}
import util.{Reporter, Utils}
import validator.DriveValidator

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.language.postfixOps

object Main {

  def execute(): Unit = {
    val config = ConfigFactory.load
    val path = config.getString("filename.path")
    val sourceFilenames = config.getString("filename.source")
    val sourceFilenamesList: List[String] = sourceFilenames.split(",").toList
    val sourceFilenamesListWithPath = sourceFilenamesList.map(path + _)
    val bikeFilename = config.getString("filename.bike")
    val generalFilename = config.getString("filename.general")
    val usageFilename = config.getString("filename.usage")
    val reader = new ParameterizedReader[DriveInfo](DriveValidator, DriveMapper)
    val reporter = Reporter(path, bikeFilename, generalFilename, usageFilename)
    val save = readAndSaveData(reader, sourceFilenamesListWithPath)
    val result = save.map(s => {
      val drives = DriveRepository.readAll()
      val stations = StationRepository.readAll()
      val driveInfo = Utils.mapToDriveInfo(drives, stations)
      processAll(reporter, driveInfo).map(reports => Writer.write(reports))
    }).flatten
    Await.ready(result, Duration.Inf)
  }

  def readAndSaveData(reader: ParameterizedReader[DriveInfo], files: List[String]): Future[List[Int]] = {
    val result = Future(files.map(fileName => {
      val lines = reader.readFile(fileName)
      val insertedStations = saveStations(lines)
      val insertedDrives = insertedStations.map(_ => saveDrives(lines)).flatten
      (insertedStations, insertedDrives)
    }))
    result.map(res => {
      val insertedStations = Future.sequence(res.map(tuple => tuple._1))
        .map(_.sum)
      val insertedDrives = Future.sequence(res.map(tuple => tuple._2))
        .map(list => list.flatMap(a => Option.option2Iterable(a)))
        .map(_.sum)
      Future.sequence(List(insertedDrives, insertedStations))
    }).flatten
  }

  def saveStations(lines: List[Option[DriveInfo]]): Future[Int] = {
    val stations = Utils.mapToStation(lines)
    Future.sequence(stations.map(station => {
      StationRepository.insert(station)
    }
    )).map(_.sum)
  }

  def saveDrives(lines: List[Option[DriveInfo]]): Future[Option[Int]] = {
    val drives = Utils.mapToDrive(lines)
    DriveRepository.insertAll(drives)
  }

  def main(args: Array[String]): Unit = {
    val start = System.nanoTime
    execute()
    val end = System.nanoTime
    print((end - start) / 1000000)
  }

  //  def processFile(reader: ParameterizedReader[DriveInfo], reporter: Reporter, sourceFilenamesListWithPath: List[String]): Future[List[Report]] = {
  //    val bikeStatsReportGenerator = new BikeStatsReportGenerator(reporter.directoryPath, reporter.bikeStatsFilename)
  //    val usageStatsReportGenerator = new UsageStatsReportGenerator(reporter.directoryPath, reporter.usageStatsFilename)
  //    val generalStatsReportGenerator = new GeneralStatsReportGenerator(DateRange("2010-09-20 12:26:08", "2010-10-26 12:26:08"), reporter.directoryPath, reporter.generalStatsFilename)
  //    val result: Future[List[Report]] = {
  //      val reports: Future[Seq[(List[(String, Int, Int)], Array[(String, Int)], (Int, Int, Int, Int, Int))]] = Future.sequence(sourceFilenamesListWithPath.par.map(
  //        sourceFilenameListWithPath => Future {
  //          val lines = reader.readFile(sourceFilenameListWithPath)
  //          (bikeStatsReportGenerator.generate(lines), usageStatsReportGenerator.generate(lines), generalStatsReportGenerator.generate(lines))
  //        }).seq)
  //      val bikeStats = reports.map(_.flatMap(_._1).groupBy(_._1).map(map => (map._1, map._2.map(_._2).sum, map._2.map(_._3).sum)).toList)
  //      val usageStats = reports.map(_.flatMap(_._2).groupBy(_._1).map(map => (map._1, map._2.map(_._2).sum)).toArray)
  //      val generalStatsFuture = reports.map(_.map(_._3))
  //      val generalStatsList = Future.sequence(
  //        List(
  //          generalStatsFuture.map(_.map(_._1).sum),
  //          generalStatsFuture.map(_.map(_._2).sum),
  //          generalStatsFuture.map(_.map(_._3).sum),
  //          generalStatsFuture.map(_.map(_._4).sum),
  //          generalStatsFuture.map(_.map(_._5).sum)
  //        )
  //      )
  //      val generalStats = generalStatsList.map(int => {
  //        val iterator = int.iterator
  //        (iterator.next(), iterator.next(), iterator.next(), iterator.next(), iterator.next())
  //      })
  //      Future.sequence(
  //        List(
  //          bikeStats.map(bikeStatsReportGenerator.generateReport),
  //          usageStats.map(usageStatsReportGenerator.generateReport),
  //          generalStats.map(generalStatsReportGenerator.generateReport)
  //        )
  //      )
  //    }
  //    result
  //  }

  def processAll(reporter: Reporter, drives: Future[List[Option[DriveInfo]]]): Future[List[Report]] = {
    val bikeStatsReportGenerator = new BikeStatsReportGenerator(reporter.directoryPath, reporter.bikeStatsFilename)
    val usageStatsReportGenerator = new UsageStatsReportGenerator(reporter.directoryPath, reporter.usageStatsFilename)
    val generalStatsReportGenerator = new GeneralStatsReportGenerator(DateRange("2010-09-20 12:26:08", "2010-10-26 12:26:08"), reporter.directoryPath, reporter.generalStatsFilename)
    val reports = process(bikeStatsReportGenerator,
      usageStatsReportGenerator,
      generalStatsReportGenerator,
      drives
    )
    //    merge all reports
    getReports(bikeStatsReportGenerator, usageStatsReportGenerator, generalStatsReportGenerator, Future(reports)).flatMap(a => Future.sequence(a))
  }

  def process(bikeStatsReportGenerator: BikeStatsReportGenerator,
              usageStatsReportGenerator: UsageStatsReportGenerator,
              generalStatsReportGenerator: GeneralStatsReportGenerator,
              lines: Future[List[Option[DriveInfo]]]): (Future[List[BikeReport]], Future[List[UsageReport]], Future[GeneralReport]) = {
    getReport(bikeStatsReportGenerator,
      usageStatsReportGenerator,
      generalStatsReportGenerator,
      lines)
  }

  def getReports(bikeStatsReportGenerator: BikeStatsReportGenerator,
                 usageStatsReportGenerator: UsageStatsReportGenerator,
                 generalStatsReportGenerator: GeneralStatsReportGenerator,
                 futureReports: Future[(Future[List[BikeReport]], Future[List[UsageReport]], Future[GeneralReport])]): Future[List[Future[Report]]] = {
    futureReports.map(reports => {
      List(
        bikeStatsReportGenerator.generateReport(reports._1),
        usageStatsReportGenerator.generateReport(reports._2),
        generalStatsReportGenerator.generateReport(reports._3)
      )
    })
  }

  //read
  def read(reader: ParameterizedReader[DriveInfo], sourceFilenameWithPath: String): Future[List[Option[DriveInfo]]] = {
    Future(reader.readFile(sourceFilenameWithPath))
  }

  //get reports
  def getReport(bikeStatsReportGenerator: BikeStatsReportGenerator,
                usageStatsReportGenerator: UsageStatsReportGenerator,
                generalStatsReportGenerator: GeneralStatsReportGenerator,
                lines: Future[List[Option[DriveInfo]]]): (Future[List[BikeReport]], Future[List[UsageReport]], Future[GeneralReport]) = {
    (bikeStatsReportGenerator.generate(lines), usageStatsReportGenerator.generate(lines), generalStatsReportGenerator.generate(lines))
  }

  //merge reports
  def merge(bikeStatsReportGenerator: BikeStatsReportGenerator,
            usageStatsReportGenerator: UsageStatsReportGenerator,
            generalStatsReportGenerator: GeneralStatsReportGenerator,
            reports: Future[Seq[(Future[List[BikeReport]], Future[List[UsageReport]], Future[GeneralReport])]]): Future[List[Future[Report]]] = {
    //merge collections
    val bikeStats: Future[Future[List[BikeReport]]] = reports.map(a =>
      Future.sequence(a.map(_._1))
        .map(_.flatten
          .groupBy(_.bikeNumber)
          .map(map =>
            BikeReport(
              map._1,
              map._2.map(_.countOfDrives).sum,
              map._2.map(_.totalDuration).sum
            )
          ).toList)
    )
    val usageStats: Future[Future[List[UsageReport]]] = reports.map(a =>
      Future.sequence(a.map(_._2))
        .map(_.flatten
          .groupBy(_.monthName)
          .map(map =>
            UsageReport(
              map._1,
              map._2.map(_.countOfDrives).sum
            )
          ).toList)
    )

    val generalStats: Future[Future[GeneralReport]] = reports.map(a =>
      Future.sequence(a.map(_._3))
        .map(g => GeneralReport(
          g.map(_.countOfDrives).sum,
          g.map(_.countOfParseErrors).sum,
          g.map(_.countOfUsagesBetweenDates).sum,
          g.map(_.countOfBicyclesUsedBetweenDates).sum,
          g.map(_.longestDrive).sum,
        )
        )
    )
    //merge general data
    Future.sequence(
      List(
        bikeStats.map(bikeStatsReportGenerator.generateReport),
        usageStats.map(usageStatsReportGenerator.generateReport),
        generalStats.map(generalStatsReportGenerator.generateReport)
      )
    )
  }
}