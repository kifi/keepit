package com.keepit.common.store

import com.keepit.common.db.Id

trait ObjectStore[A, B] {

  /** Adds a new key/value pair to this map.
   *  If the map already contains a
   *  mapping for the key, it will be overridden by the new value.
   *  @param    kv the key/value pair.
   *  @return   the map itself
   */
  def += (kv: (A, B)): this.type

  /** Removes a key from this map.
   *  @param    key the key to be removed
   *  @return   the map itself.
   */
  def -= (key: A): this.type

  /** Optionally returns the value associated with a key.
   *
   *  @param  key    the key value
   *  @return an option value containing the value associated with `key` in this map,
   *          or `None` if none exists.
   */
  def get(key: A): Option[B]

}
