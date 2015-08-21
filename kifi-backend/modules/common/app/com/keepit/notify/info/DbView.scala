package com.keepit.notify.info

import com.keepit.common.db.Id
import com.keepit.common.store.ImagePath
import com.keepit.model._

/**
 * Represents a view, or subset, of the shoebox database, mostly from the perspective of Eliza.
 *
 * The implementation uses a map from keys to maps from ids to results. While the underlying maps are
 * untyped, types are enforced through the public facing methods [[add]] and [[lookup]].
 */
class DbView(private val keyMap: Map[DbViewKey[_, _], Map[Id[_], Any]]) {

  def add[M <: HasId[M], R](key: DbViewKey[M, R], id: Id[M], result: R): DbView = {
    val objMap = keyMap.getOrElse(key, Map.empty)
    val assocObjMap = objMap + (id -> result)
    new DbView(keyMap + (key -> assocObjMap))
  }

 def lookup[M <: HasId[M], R](key: DbViewKey[M, R], id: Id[M]): R = keyMap(key)(id).asInstanceOf[R]

}

object DbView {

  /**
   * Constructs an empty MapDbView.
   *
   * This is the only way to construct a DbView from the outside.
   */
  def apply() = new DbView(Map.empty)

}

/**
 * Represents a key which generates DB view requests.
 */
class DbViewKey[M <: HasId[M], R] {

  /**
   * Indicates that the given model already has already been fetched in a potential DB view.
   */
  def existing(model: M): ExistingDbViewModel[M] = ExistingDbViewModel(this, model)

  /**
   * Builds a request using the given model.
   */
  def apply(id: Id[M]): DbViewRequest[M, R] = DbViewRequest(this, id)

}

/**
 * Contains a listing of Db view keys which can be used to lookup models.
 */
object DbViewKey {

  def apply[M <: HasId[M], R] = new DbViewKey[M, R]

  val user = DbViewKey[User, User]
  val library = DbViewKey[Library, Library]
  val userImageUrl = DbViewKey[User, String]
  val keep = DbViewKey[Keep, Keep]
  val libraryUrl = DbViewKey[Library, String]
  val libraryInfo = DbViewKey[Library, LibraryNotificationInfo]
  val libraryOwner = DbViewKey[Library, User]
  val organization = DbViewKey[Organization, Organization]
  val organizationInfo = DbViewKey[Organization, OrganizationNotificationInfo]

}

/**
 * Represents a request for a certain model in a db view.
 *
 * @param key The key of the request
 * @param id The id of the model to lookup
 * @tparam M The type of model to lookup
 * @tparam R The type of result of the request
 */
case class DbViewRequest[M <: HasId[M], R](key: DbViewKey[M, R], id: Id[M]) {

  /**
   * Actually look up the request in a db view. Because the db view  is untyped,
   * a final cast is done.
   *
   * @param view The subset to look up in
   * @return The looked up result
   */
  def lookup(view: DbView): R = view.lookup(key, id)

}

/**
 * Represents a wrapper of a function that requests a whole bunch of items from a potential db view, then
 * constructs a value.
 */
case class UsingDbView[A](requests: Seq[DbViewRequest[_, _]])(fn: DbView => A)

/**
 * Represents that a certain model already exists and can be used for DB view requests.
 */
case class ExistingDbViewModel[M <: HasId[M]](key: DbViewKey[M, _], model: M)

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
