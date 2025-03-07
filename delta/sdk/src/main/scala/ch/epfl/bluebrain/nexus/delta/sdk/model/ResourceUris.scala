package ch.epfl.bluebrain.nexus.delta.sdk.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}

/**
  * Holds information about the different access Uri of a resource
  */
sealed trait ResourceUris extends Product with Serializable {

  /**
    * @return
    *   the relative access [[Uri]]
    */
  def relativeAccessUri: Uri

  /**
    * @return
    *   the access [[Uri]]
    */
  def accessUri(implicit base: BaseUri): Uri =
    relativeAccessUri.resolvedAgainst(base.endpoint.finalSlash())
}

object ResourceUris {

  /**
    * A resource that is not rooted in a project
    */
  final case class RootResourceUris(relativeAccessUri: Uri, relativeAccessUriShortForm: Uri) extends ResourceUris

  /**
    * A resource that is rooted in a project
    */
  final case class ResourceInProjectUris(
      projectRef: ProjectRef,
      relativeAccessUri: Uri
  ) extends ResourceUris {
    def incoming(implicit base: BaseUri): Uri = accessUri / "incoming"
    def outgoing(implicit base: BaseUri): Uri = accessUri / "outgoing"
    def project(implicit base: BaseUri): Uri  = ResourceUris.project(projectRef).accessUri
  }

  /**
    * A resource that is rooted in a project but not persisted or indexed.
    */
  final case class EphemeralResourceInProjectUris(
      projectRef: ProjectRef,
      relativeAccessUri: Uri
  ) extends ResourceUris {
    def project(implicit base: BaseUri): Uri = ResourceUris.project(projectRef).accessUri
  }

  /**
    * A resource that is rooted in a project and a schema: the system resources that are validated against a schema
    */
  final case class ResourceInProjectAndSchemaUris(
      projectRef: ProjectRef,
      schemaProjectRef: ProjectRef,
      relativeAccessUri: Uri
  ) extends ResourceUris {
    def incoming(implicit base: BaseUri): Uri      = accessUri / "incoming"
    def outgoing(implicit base: BaseUri): Uri      = accessUri / "outgoing"
    def project(implicit base: BaseUri): Uri       = ResourceUris.project(projectRef).accessUri
    def schemaProject(implicit base: BaseUri): Uri = ResourceUris.project(schemaProjectRef).accessUri
  }

  /**
    * Constructs [[ResourceUris]] from the passed arguments and an ''id'' that can be compacted based on the project
    * mappings and base.
    *
    * @param resourceTypeSegment
    *   the resource type segment: resolvers, schemas, resources, etc
    * @param projectRef
    *   the project reference
    * @param id
    *   the id that can be compacted
    */
  final def apply(resourceTypeSegment: String, projectRef: ProjectRef, id: Iri): ResourceUris = {
    val relative = Uri(resourceTypeSegment) / projectRef.organization.value / projectRef.project.value
    ResourceInProjectUris(projectRef, relative / id.toString)
  }

  /**
    * Constructs [[ResourceUris]] from a relative [[Uri]].
    *
    * @param relative
    *   the relative base [[Uri]]
    */
  final def apply(relative: Uri): ResourceUris =
    RootResourceUris(relative, relative)

  /**
    * Constructs [[ResourceUris]] from the passed arguments. The ''id'' and ''schema'' can be compacted based on the
    * project mappings and base.
    *
    * @param resourceTypeSegment
    *   the resource type segment: resolvers, schemas, resources, etc
    * @param projectRef
    *   the project reference
    * @param schemaProject
    *   the schema project reference
    * @param id
    *   the id that can be compacted
    */
  private def apply(
      resourceTypeSegment: String,
      projectRef: ProjectRef,
      schemaProject: ProjectRef,
      id: Iri
  ): ResourceUris = {
    val relative = Uri(resourceTypeSegment) / projectRef.organization.value / projectRef.project.value
    ResourceInProjectAndSchemaUris(
      projectRef,
      schemaProject,
      relative / "_" / id.toString
    )
  }

  /**
    * Resource uris for permissions
    */
  val permissions: ResourceUris =
    apply("permissions")

  /**
    * Resource uris for an acl
    */
  def acl(address: AclAddress): ResourceUris =
    address match {
      case AclAddress.Root                  => apply("acls")
      case AclAddress.Organization(org)     => apply(s"acls/$org")
      case AclAddress.Project(org, project) => apply(s"acls/$org/$project")
    }

  /**
    * Resource uris for a realm
    */
  def realm(label: Label): ResourceUris =
    apply(s"realms/$label")

  /**
    * Resource uris for an organization
    */
  def organization(label: Label): ResourceUris =
    apply(s"orgs/$label")

  /**
    * Resource uris for a project
    */
  def project(ref: ProjectRef): ResourceUris =
    apply(s"projects/$ref")

  /**
    * Resource uris for a resource
    */
  def resource(projectRef: ProjectRef, schemaProject: ProjectRef, id: Iri): ResourceUris =
    apply("resources", projectRef, schemaProject, id)

  /**
    * Resource uris for a schema
    */
  def schema(ref: ProjectRef, id: Iri): ResourceUris =
    apply("schemas", ref, id)

  /**
    * Resource uris for a resolver
    */
  def resolver(ref: ProjectRef, id: Iri): ResourceUris =
    apply("resolvers", ref, id)

  /**
    * Resource uris for ephemeral resources that are scoped to a project.
    */
  def ephemeral(
      resourceTypeSegment: String,
      ref: ProjectRef,
      id: Iri
  ): ResourceUris = {
    val relative       = Uri(resourceTypeSegment) / ref.organization.value / ref.project.value
    val relativeAccess = relative / id.toString
    EphemeralResourceInProjectUris(ref, relativeAccess)
  }
}
