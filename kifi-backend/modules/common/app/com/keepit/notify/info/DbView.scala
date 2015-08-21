package com.keepit.notify.info

import com.keepit.common.db.Id
import com.keepit.common.store.ImagePath
import com.keepit.model._

/**
 * Represents a view, or subset, of the shoebox database, mostly from the perspective of Eliza.
 */
trait DbView {

  def lookup(kind: String, id: Id[_]): Any

}

/**
 * Represents a request for a certain model in a db view.
 *
 * @param kind The kind of the request
 * @param id The id of the model to lookup
 * @tparam M The type of model to lookup
 * @tparam R The type of result of the request
 */
case class DbViewRequest[M <: HasId[M], R](kind: String, id: Id[M]) {

  /**
   * Actually look up the request in a db view. Because the db view  is untyped,
   * a final cast is done.
   *
   * @param subset The subset to look up in
   * @return The looked up result
   */
  def lookup(subset: DbView): R = subset.lookup(kind, id).asInstanceOf[R]

}

/**
 * Represents that a certain model already exists and can be used for DB view requests.
 */
case class ExistingDbViewModel[M <: HasId[M]](kind: String, model: M)

/**
 * Represents a key which generates DB view requests.
 */
case class DbViewKey[M <: HasId[M], R](kind: String) {

  /**
   * Indicates that the given model already has already been fetched in a potential DB view.
   */
  def existing(model: M): ExistingDbViewModel[M] = ExistingDbViewModel(kind, model)

  /**
   * Builds a request using the given model.
   */
  def apply(id: Id[M]): DbViewRequest[M, R] = DbViewRequest(kind, id)

}

/**
 * Contains a listing of Db view keys which can be used to lookup models.
 */
object DbViewKey {

  val user = DbViewKey[User, User]("user")
  val library = DbViewKey[Library, Library]("library")
  val userImageUrl = DbViewKey[User, String]("userImageUrl")
  val keep = DbViewKey[Keep, Keep]("keep")
  val libraryUrl = DbViewKey[Library, String]("libraryUrl")
  val libraryInfo = DbViewKey[Library, LibraryNotificationInfo]("libraryInfo")
  val libraryOwner = DbViewKey[Library, User]("libraryOwner")
  val organization = DbViewKey[Organization, Organization]("organization")
  val organizationInfo = DbViewKey[Organization, OrganizationNotificationInfo]("organizationInfo")

}

/**
 * An implementation of a db view using a simple object/kind map.
 */
class MapDbView(
    val objMap: Map[String, Map[Id[_], Any]]) extends DbView {

  override def lookup(kind: String, id: Id[_]): Any = objMap(kind)(id)

}

/**
 * Represents a wrapper of a function that requests a whole bunch of items from a potential db view, then
 * constructs a value.
 */
case class UsingDbView[A](requests: Seq[DbViewRequest[_, _]])(fn: DbView => A)

/**
 * Represents a whole bunch of items that have already been fetched and exist in a DB view, along with a value that
 * is constructed in the context of that view. The value can be used on its own, or additional properties may be derived
 * from the value with thehelp of a Db view.
 *
 * The prime example is a [[com.keepit.notify.model.event.NotificationEvent]], where constructing one gives most of the
 * information needed to generate its resulting display information, [[NotificationInfo]]. By wrapping the parameters to
 * the event in this class, all the potential additional request for information can be reduced.
 */
case class ExistingDbView[A](existing: Seq[ExistingDbViewModel[_]])(value: A)
