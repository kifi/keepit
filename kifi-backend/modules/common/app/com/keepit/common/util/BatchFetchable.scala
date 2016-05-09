package com.keepit.common.util

import com.keepit.common.db.Id
import com.keepit.common.util.BatchFetchable.{ Keys, Values }
import com.keepit.model._
import com.keepit.social.BasicUser
import play.api.libs.functional.{ FunctionalCanBuild, Functor, ~ }
import play.api.libs.json._

final case class BatchFetchable[T](keys: Keys, f: Values => T) {
  def map[S](g: T => S) = this.copy(f = f andThen g)
}

object BatchFetchable {
  final case class Keys(users: Set[Id[User]], libraries: Set[Id[Library]], orgs: Set[Id[Organization]]) {
    def ++(that: Keys) = Keys(this.users ++ that.users, this.libraries ++ that.libraries, this.orgs ++ that.orgs)
  }
  object Keys {
    val empty = Keys(Set.empty, Set.empty, Set.empty)
    def unions(ks: Traversable[Keys]): Keys = ks.fold(empty)(_ ++ _)
  }

  final case class Values(users: Map[Id[User], BasicUser], libs: Map[Id[Library], BasicLibrary], orgs: Map[Id[Organization], BasicOrganization])
  object Values {
    val empty = Values(Map.empty, Map.empty, Map.empty)
    implicit val format: Format[Values] = Json.format[Values]
  }

  val empty = BatchFetchable[Unit](Keys.empty, _ => ())
  def trivial[T](t: T) = BatchFetchable.empty.map(_ => t)
  def user(id: Id[User]): BatchFetchable[Option[BasicUser]] = BatchFetchable(Keys(Set(id), Set.empty, Set.empty), _.users.get(id))
  def userOpt(idOpt: Option[Id[User]]): BatchFetchable[Option[BasicUser]] = idOpt.map(user).getOrElse(trivial(None))
  def library(id: Id[Library]): BatchFetchable[Option[BasicLibrary]] = BatchFetchable(Keys(Set.empty, Set(id), Set.empty), _.libs.get(id))
  def libraryOpt(idOpt: Option[Id[Library]]): BatchFetchable[Option[BasicLibrary]] = idOpt.map(library).getOrElse(trivial(None))
  def org(id: Id[Organization]): BatchFetchable[Option[BasicOrganization]] = BatchFetchable(Keys(Set.empty, Set.empty, Set(id)), _.orgs.get(id))
  def orgOpt(idOpt: Option[Id[Organization]]): BatchFetchable[Option[BasicOrganization]] = idOpt.map(org).getOrElse(trivial(None))

  def seq[T](xs: Seq[BatchFetchable[T]]): BatchFetchable[Seq[T]] =
    BatchFetchable(Keys.unions(xs.map(_.keys)), vs => xs.map(_.f(vs)))

  def flipMap[K, V](xs: Map[K, BatchFetchable[V]]): BatchFetchable[Map[K, V]] =
    BatchFetchable(Keys.unions(xs.values.map(_.keys)), vs => xs.mapValues(_.f(vs)))

  implicit val bfFCB: FunctionalCanBuild[BatchFetchable] = new FunctionalCanBuild[BatchFetchable] {
    def apply[A, B](ma: BatchFetchable[A], mb: BatchFetchable[B]): BatchFetchable[~[A, B]] = {
      BatchFetchable[~[A, B]](ma.keys ++ mb.keys, vs => new ~(ma.f(vs), mb.f(vs)))
    }
  }
  implicit val bfFunctor: Functor[BatchFetchable] = new Functor[BatchFetchable] {
    def fmap[A, B](ma: BatchFetchable[A], f: A => B): BatchFetchable[B] = ma.map(f)
  }
}
