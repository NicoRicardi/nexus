package ch.epfl.bluebrain.nexus.delta.sdk.model.permissions

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.PermissionsResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Identity, ResourceF, ResourceRef}
import org.apache.jena.iri.IRI

// $COVERAGE-OFF$
/**
  * Enumeration of Permissions states.
  */
sealed trait PermissionsState extends Product with Serializable {

  /**
    * @return the current state revision
    */
  def rev: Long

  /**
    * @return the current deprecation status (always false for permissions)
    */
  final def deprecated: Boolean = false

  /**
    * @return the schema reference that permissions conforms to
    */
  final def schema: ResourceRef = Latest(schemas.permissions)

  /**
    * @return the collection of known types of permissions resources
    */
  final def types: Set[IRI] = Set(nxv.Permissions)

  /**
    * Converts the state into a resource representation.
    *
    * @param id      the resource identifier
    * @param minimum minimum set of permissions (static configuration)
    */
  def toResource(id: IRI, minimum: Set[Permission]): PermissionsResource
}
// $COVERAGE-ON$

object PermissionsState {

  /**
    * Initial state type.
    */
  type Initial = Initial.type

  /**
    * Initial state for the permission set.
    */
  final case object Initial extends PermissionsState {
    override val rev: Long = 0L

    override def toResource(id: IRI, minimum: Set[Permission]): PermissionsResource =
      ResourceF(
        id = id,
        rev = rev,
        types = types,
        deprecated = deprecated,
        createdAt = Instant.EPOCH,
        createdBy = Identity.Anonymous,
        updatedAt = Instant.EPOCH,
        updatedBy = Identity.Anonymous,
        schema = schema,
        value = minimum
      )
  }

  /**
    * The "current" state for the permission set, available once at least one event was emitted.
    *
    * @param rev         the current state revision
    * @param permissions the permission set
    * @param createdAt   the instant when the resource was created
    * @param createdBy   the subject that created the resource
    * @param updatedAt   the instant when the resource was last updated
    * @param updatedBy   the subject that last updated the resource
    */
  final case class Current(
      rev: Long,
      permissions: Set[Permission],
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends PermissionsState {

    override def toResource(id: IRI, minimum: Set[Permission]): PermissionsResource =
      ResourceF(
        id = id,
        rev = rev,
        types = types,
        deprecated = deprecated,
        createdAt = createdAt,
        createdBy = createdBy,
        updatedAt = updatedAt,
        updatedBy = updatedBy,
        schema = schema,
        value = permissions ++ minimum
      )
  }
}
