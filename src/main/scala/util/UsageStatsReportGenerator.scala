package util

import entity.{DriveInfo, Report}

import java.time.Month
import scala.collection.immutable.List

class UsageStatsReportGenerator(directoryPath: String, usageStatsFilename: String) extends Reporter {
  override def generate(list: List[Option[DriveInfo]]): Report = {
    val statisticsWithNoErrorLines = Utils.getStatisticsWithNoErrorLines(list)
    generateMonthlyDrivesStatisticsReport(statisticsWithNoErrorLines)
  }

  private def generateMonthlyDrivesStatisticsReport(list: List[Option[DriveInfo]]): Report = {
    val stats = Month.values().map(month => ("\"" + month + "\"", "\"" + monthlyDrivesStatistics(month, list) + "\""))
    val report = stats.mkString("\n")
    Report(directoryPath + usageStatsFilename, report.replaceAll("\\(", "").replaceAll("\\)", ""))
  }

  private def monthlyDrivesStatistics(month: Month, list: List[Option[DriveInfo]]): Int = {
    list.count(line => line.get.startDate.getMonth.equals(month))
  }
}
