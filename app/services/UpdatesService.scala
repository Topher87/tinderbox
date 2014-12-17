package services

import play.api.Logger
import scala.collection.mutable.Map
import scala.concurrent._
import scala.concurrent.duration._
import org.mapdb._
import utils.tinder.TinderApi
import utils.tinder.model._
import models.{MatchUpdate, Notification}
import java.util.concurrent.ConcurrentNavigableMap
import java.util.{TimeZone, Date}
import java.text.{SimpleDateFormat, DateFormat}

/**
 * A service that pulls, caches, and notifies of updates.
 */
object UpdatesService {

  /**
   * History and notification storage
   */
  private val matches: ConcurrentNavigableMap[String, List[Match]] = MapDB.db.getTreeMap("matches")

  private val notifications: ConcurrentNavigableMap[String, List[Notification]] = MapDB.db.getTreeMap("notifications")

  private val unreadCounts: ConcurrentNavigableMap[String, Map[String, Int]] = MapDB.db.getTreeMap("unread_counts")

  /**
   * Required for timing updates properly
   */
  private var lastActivity: ConcurrentNavigableMap[String, Date] = MapDB.db.getTreeMap("last_activity")

  // timed actors for retrieving updates will go here

  /**
   * Performs a full update of the history cache.
   *
   * Note: this resets unread counts to zero since we let updates handle that for us.
   * @param xAuthToken
   */
  private def syncHistory(xAuthToken: String): Option[List[Match]] = {
    val tinderApi = new TinderApi(Some(xAuthToken))
    val result = Await.result(tinderApi.getHistory, 30 seconds)
    result match {
      case Left(error) =>
        Logger.error("An error occurred retrieving full history for %s.".format(xAuthToken))
        Logger.error("Reason: "+error.error)
        None
      case Right(history) =>
        // update the last activity variable
        history.last_activity_date.map{ date => lastActivity.put(xAuthToken, tinderApi.ISO8601.parse(date)) }
        val data = history.matches
        if(data.size>0) matches.put(xAuthToken, data)
        Some(data)
    }
  }

  /**
   * Grabs recent updates and syncs them with history.
   * @param xAuthToken
   * @return
   */
  private def syncUpdates(xAuthToken: String): Option[(Map[String, List[Message]], List[Notification], Map[String, Int])] = {
    val tinderApi = new TinderApi(Some(xAuthToken))
    val result = Await.result(tinderApi.getUpdates(fetchLastActivity(xAuthToken).getOrElse(new Date())), 10 seconds)
    result match {
      case Left(error) =>
        Logger.error("An error occurred retrieving full history for %s.".format(xAuthToken))
        Logger.error("Reason: "+error.error)
        None
      case Right(history) =>
        val messages = Map(history.matches.map( m => (m._id, m.messages)).toMap.toSeq: _*)
        // first update match history TODO
        history.matches.foreach { m =>
          putMessages(xAuthToken, m._id, m.messages)
        }
        history.last_activity_date.map{ date => lastActivity.put(xAuthToken, tinderApi.ISO8601.parse(date)) }
        // then create notifications
        val notificationList = history.matches.map { m =>
          new Notification(
            "messages",
            m._id,
            m.person.map(_.name).getOrElse("Someone"),
            "%s sent you %s messages.".format(m.person.map(_.name).getOrElse("Someone"), m.messages.size),
            m.messages.size
          )
        }
        notifications.put(xAuthToken, notificationList)
        // finally append new notifications to existing read counts
        val unreads = unreadCounts.get(xAuthToken) match {
          case null => Map[String, Int]()
          case counts =>
            counts.map { n =>
              val countAppend = notificationList.filter(o => o.associateId==n._1).headOption match {
                case None => 0
                case Some(o) => o.size
              }
              (n._1, countAppend+n._2 )
            }
        }
        unreadCounts.put(xAuthToken, unreads)
        Some((messages, notificationList, unreads))
    }
  }

  /**
   * Updates a list of messages into match history.
   * @param xAuthToken
   * @param matchId
   * @param messages
   */
  def putMessages(xAuthToken: String, matchId: String, messages: List[Message]) {
    fetchHistory(xAuthToken) match {
      case None => // do nothing, might not exist
      case Some(history) =>
        history.filter(m => m._id==matchId).headOption match {
          case None => // the match isn't in history, there are bigger problems
          case Some(m) =>
            val newMatch = m.copy(messages = messages)
            val matchList = history.filterNot(m => m._id==matchId) ::: List(newMatch)
            matches.put(xAuthToken, matchList)
        }
    }
  }

  /**
   * Retrieves full match history and all users.
   * @param xAuthToken
   * @return
   */
  def fetchHistory(xAuthToken: String): Option[List[Match]] = {
    matches.get(xAuthToken) match {
      case null =>
        syncHistory(xAuthToken)
      case matches =>
        Some(matches)
    }
  }

  /**
   * Retrieve the last date an update was valid.
   * @param xAuthToken
   * @return
   */
  def fetchLastActivity(xAuthToken: String): Option[Date] = {
    lastActivity.get(xAuthToken) match {
      case null =>
        None
      case date =>
        Some(date)
    }
  }

  /**
   * Get a list of raw updates for a user.
   * @param xAuthToken
   * @return
   */
  def fetchUpdates(xAuthToken: String): Option[(Map[String, List[Message]], List[Notification], Map[String, Int])] = {
    syncUpdates(xAuthToken)
  }

  /**
   * Get a list of notifications for a user.
   * @param xAuthToken
   * @return
   */
  def fetchNotifications(xAuthToken: String): Option[List[Notification]] = {
    notifications.get(xAuthToken) match {
      case null => None
      case counts => Some(counts)
    }
  }

  /**
   * Asynchronously clears all notifications for a user.
   * @param xAuthToken
   */
  def clearNotifications(xAuthToken: String) {
    notifications.put(xAuthToken, List[Notification]())
  }

  /**
   * Grabs a list of unread counts for messages.
   * @param xAuthToken
   * @return
   */
  def fetchUnreadCounts(xAuthToken: String): Option[Map[String, Int]] = {
    unreadCounts.get(xAuthToken) match {
      case null => None
      case counts => Some(counts)
    }
  }

  /**
   * Asynchronously clears unread count for a specific match ID.
   * @param xAuthToken
   * @param matchId
   */
  def clearUnreadCount(xAuthToken: String, matchId: String) {
    fetchUnreadCounts(xAuthToken) match {
      case None => // do nothing
      case Some(counts) =>
        counts.put(matchId, 0)
        unreadCounts.put(xAuthToken, counts)
    }
  }

}