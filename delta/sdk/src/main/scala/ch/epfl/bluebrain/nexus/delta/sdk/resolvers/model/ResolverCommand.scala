package ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.Json

/**
  * Enumeration of Resolver command types.
  */
sealed trait ResolverCommand extends Product with Serializable {

  /**
    * @return
    *   the project where the resolver belongs to
    */
  def project: ProjectRef

  /**
    * @return
    *   the resolver identifier
    */
  def id: Iri

  /**
    * @return
    *   the last known revision of the resolver
    */
  def rev: Int

  /**
    * @return
    *   the identity associated to this command
    */
  def subject: Subject
}

object ResolverCommand {

  /**
    * Command to create a new resolver
    * @param id
    *   the resolver identifier
    * @param project
    *   the project the resolver belongs to
    * @param value
    *   additional fields to configure the resolver
    * @param source
    *   the representation of the resolver as posted by the subject
    * @param caller
    *   the caller associated to this command
    */
  final case class CreateResolver(
      id: Iri,
      project: ProjectRef,
      value: ResolverValue,
      source: Json,
      caller: Caller
  ) extends ResolverCommand {

    override def rev: Int = 0

    override def subject: Subject = caller.subject
  }

  /**
    * Command to update an existing resolver
    * @param id
    *   the resolver identifier
    * @param project
    *   the project the resolver belongs to
    * @param value
    *   additional fields to configure the resolver
    * @param source
    *   the representation of the resolver as posted by the subject
    * @param rev
    *   the last known revision of the resolver
    * @param caller
    *   the caller associated to this command
    */
  final case class UpdateResolver(
      id: Iri,
      project: ProjectRef,
      value: ResolverValue,
      source: Json,
      rev: Int,
      caller: Caller
  ) extends ResolverCommand {
    override def subject: Subject = caller.subject
  }

  /**
    * Command to tag a resolver
    *
    * @param id
    *   the resolver identifier
    * @param project
    *   the project the resolver belongs to
    * @param targetRev
    *   the revision that is being aliased with the provided ''tag''
    * @param tag
    *   the tag of the alias for the provided ''tagRev''
    * @param rev
    *   the last known revision of the resolver
    * @param subject
    *   the identity associated to this command
    */
  final case class TagResolver(
      id: Iri,
      project: ProjectRef,
      targetRev: Int,
      tag: UserTag,
      rev: Int,
      subject: Subject
  ) extends ResolverCommand

  /**
    * Command to deprecate a resolver
    * @param id
    *   the resolver identifier
    * @param project
    *   the project the resolver belongs to
    * @param rev
    *   the last known revision of the resolver
    * @param subject
    *   the identity associated to this command
    */
  final case class DeprecateResolver(id: Iri, project: ProjectRef, rev: Int, subject: Subject) extends ResolverCommand

}
