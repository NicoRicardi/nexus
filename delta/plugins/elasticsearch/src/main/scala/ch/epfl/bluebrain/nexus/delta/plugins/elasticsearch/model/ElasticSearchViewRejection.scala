package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.kernel.error.Rejection
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.ConversionError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.{RdfError, Vocabulary}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionErr
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}

/**
  * Enumeration of ElasticSearch view rejection types.
  *
  * @param reason
  *   a descriptive message as to why the rejection occurred
  */
sealed abstract class ElasticSearchViewRejection(val reason: String) extends Rejection

object ElasticSearchViewRejection {

  /**
    * Rejection returned when a subject intends to retrieve a view at a specific revision, but the provided revision
    * does not exist.
    *
    * @param provided
    *   the provided revision
    * @param current
    *   the last known revision
    */
  final case class RevisionNotFound(provided: Int, current: Int)
      extends ElasticSearchViewRejection(
        s"Revision requested '$provided' not found, last known revision is '$current'."
      )

  /**
    * Rejection returned when a subject intends to retrieve a view at a specific tag, but the provided tag does not
    * exist.
    *
    * @param tag
    *   the provided tag
    */
  final case class TagNotFound(tag: UserTag) extends ElasticSearchViewRejection(s"Tag requested '$tag' not found.")

  /**
    * Rejection returned when attempting to create an elastic search view but the id already exists.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project it belongs to
    */
  final case class ResourceAlreadyExists(id: Iri, project: ProjectRef)
      extends ElasticSearchViewRejection(s"Resource '$id' already exists in project '$project'.")

  /**
    * Rejection returned when a view that doesn't exist.
    *
    * @param id
    *   the view id
    */
  final case class ViewNotFound(id: Iri, project: ProjectRef)
      extends ElasticSearchViewRejection(s"ElasticSearch view '$id' not found in project '$project'.")

  /**
    * Rejection returned when attempting to update/deprecate a view that is already deprecated.
    *
    * @param id
    *   the view id
    */
  final case class ViewIsDeprecated(id: Iri)
      extends ElasticSearchViewRejection(s"ElasticSearch view '$id' is deprecated.")

  /**
    * Rejection returned when a subject intends to perform an operation on the current view, but either provided an
    * incorrect revision or a concurrent update won over this attempt.
    *
    * @param provided
    *   the provided revision
    * @param expected
    *   the expected revision
    */
  final case class IncorrectRev(provided: Int, expected: Int)
      extends ElasticSearchViewRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the view may have been updated since last seen."
      )

  /**
    * Signals a rejection caused when interacting with other APIs when fetching a resource
    */
  final case class ProjectContextRejection(rejection: ContextRejection)
      extends ElasticSearchViewRejection("Something went wrong while interacting with another module.")

  /**
    * Signals a rejection caused by an attempt to create or update an ElasticSearch view with a permission that is not
    * defined in the permission set singleton.
    *
    * @param permission
    *   the provided permission
    */
  final case class PermissionIsNotDefined(permission: Permission)
      extends ElasticSearchViewRejection(
        s"The provided permission '${permission.value}' is not defined in the collection of allowed permissions."
      )

  /**
    * Rejection returned when view of type ''expected'' was desired but a view ''provided'' was provided instead. This
    * can happen during update of a view when attempting to change the type or during fetch of a particular type of view
    *
    * @param id
    *   the view id
    */
  final case class DifferentElasticSearchViewType(
      id: String,
      provided: ElasticSearchViewType,
      expected: ElasticSearchViewType
  ) extends ElasticSearchViewRejection(
        s"Incorrect ElasticSearch view '$id' type: '$provided' provided, expected '$expected'."
      )

  /**
    * Rejection returned when the provided ElasticSearch mapping for an IndexingElasticSearchView is invalid.
    */
  final case class InvalidElasticSearchIndexPayload(details: Option[Json])
      extends ElasticSearchViewRejection("The provided ElasticSearch mapping value is invalid.")

  final case class InvalidPipeline(error: ProjectionErr)
      extends ElasticSearchViewRejection("The provided pipeline is invalid.")

  /**
    * Rejection returned when at least one of the provided view references for an AggregateElasticSearchView does not
    * exist or is deprecated.
    *
    * @param views
    *   the offending view references
    */
  final case class InvalidViewReferences(views: Set[ViewRef])
      extends ElasticSearchViewRejection(
        s"At least one view reference does not exist or is deprecated."
      )

  /**
    * Rejection returned when attempting to create an ElasticSearchView where the passed id does not match the id on the
    * source json document.
    *
    * @param id
    *   the view identifier
    * @param sourceId
    *   the view identifier in the source json document
    */
  final case class UnexpectedElasticSearchViewId(id: Iri, sourceId: Iri)
      extends ElasticSearchViewRejection(
        s"The provided ElasticSearch view '$id' does not match the id '$sourceId' in the source document."
      )

  /**
    * Rejection returned when attempting to interact with an ElasticSearchView while providing an id that cannot be
    * resolved to an Iri.
    *
    * @param id
    *   the view identifier
    */
  final case class InvalidElasticSearchViewId(id: String)
      extends ElasticSearchViewRejection(s"ElasticSearch view identifier '$id' cannot be expanded to an Iri.")

  /**
    * Rejection returned when attempting to create an ElasticSearchView while providing an id that is blank.
    */
  final case object BlankElasticSearchViewId
      extends ElasticSearchViewRejection(s"Elastic search view identifier cannot be blank.")

  /**
    * Rejection when attempting to decode an expanded JsonLD as an ElasticSearchViewValue.
    *
    * @param error
    *   the decoder error
    */
  final case class DecodingFailed(error: JsonLdDecoderError) extends ElasticSearchViewRejection(error.getMessage)

  /**
    * Signals an error converting the source Json document to a JsonLD document.
    */
  final case class InvalidJsonLdFormat(id: Option[Iri], rdfError: RdfError)
      extends ElasticSearchViewRejection(
        s"The provided ElasticSearch view JSON document${id.fold("")(id => s" with id '$id'")} cannot be interpreted as a JSON-LD document."
      )

  /**
    * Signals a rejection caused when interacting with the elasticserch client
    */
  final case class WrappedElasticSearchClientError(error: HttpClientError)
      extends ElasticSearchViewRejection("Error while interacting with the underlying ElasticSearch index")

  /**
    * Rejection returned when attempting to interact with a resource providing an id that cannot be resolved to an Iri.
    *
    * @param id
    *   the resource identifier
    */
  final case class InvalidResourceId(id: String)
      extends ElasticSearchViewRejection(s"Resource identifier '$id' cannot be expanded to an Iri.")

  /**
    * Rejection returned when too many view references are specified on an aggregated view.
    *
    * @param provided
    *   the number of view references specified
    * @param max
    *   the maximum number of aggregated views allowed
    */
  final case class TooManyViewReferences(provided: Int, max: Int)
      extends ElasticSearchViewRejection(s"$provided exceeds the maximum allowed number of view references ($max).")

  implicit final val jsonLdRejectionMapper: Mapper[JsonLdRejection, ElasticSearchViewRejection] = {
    case JsonLdRejection.UnexpectedId(id, sourceId)        => UnexpectedElasticSearchViewId(id, sourceId)
    case JsonLdRejection.InvalidJsonLdFormat(id, rdfError) => InvalidJsonLdFormat(id, rdfError)
    case JsonLdRejection.DecodingFailed(error)             => DecodingFailed(error)
    case JsonLdRejection.BlankId                           => BlankElasticSearchViewId
  }

  implicit val elasticSearchRejectionEncoder: Encoder.AsObject[ElasticSearchViewRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case WrappedElasticSearchClientError(rejection)          =>
          rejection.jsonBody.flatMap(_.asObject).getOrElse(obj.add(keywords.tpe, "ElasticSearchClientError".asJson))
        case ProjectContextRejection(rejection)                  => rejection.asJsonObject
        case InvalidJsonLdFormat(_, ConversionError(details, _)) => obj.add("details", details.asJson)
        case InvalidJsonLdFormat(_, rdf)                         => obj.add("rdf", rdf.asJson)
        case IncorrectRev(provided, expected)                    => obj.add("provided", provided.asJson).add("expected", expected.asJson)
        case InvalidElasticSearchIndexPayload(details)           => obj.addIfExists("details", details)
        case InvalidViewReferences(views)                        => obj.add("views", views.asJson)
        case InvalidPipeline(error)                              => obj.add("details", error.reason.asJson)
        case _: ViewNotFound                                     => obj.add(keywords.tpe, "ResourceNotFound".asJson)
        case _                                                   => obj
      }
    }

  implicit final val viewRejectionJsonLdEncoder: JsonLdEncoder[ElasticSearchViewRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(Vocabulary.contexts.error))

  implicit val elasticSearchViewRejectionHttpResponseFields: HttpResponseFields[ElasticSearchViewRejection] =
    HttpResponseFields {
      case RevisionNotFound(_, _)                 => StatusCodes.NotFound
      case TagNotFound(_)                         => StatusCodes.NotFound
      case ViewNotFound(_, _)                     => StatusCodes.NotFound
      case ResourceAlreadyExists(_, _)            => StatusCodes.Conflict
      case IncorrectRev(_, _)                     => StatusCodes.Conflict
      case ProjectContextRejection(rej)           => rej.status
      case WrappedElasticSearchClientError(error) => error.errorCode.getOrElse(StatusCodes.InternalServerError)
      case _                                      => StatusCodes.BadRequest
    }

}
