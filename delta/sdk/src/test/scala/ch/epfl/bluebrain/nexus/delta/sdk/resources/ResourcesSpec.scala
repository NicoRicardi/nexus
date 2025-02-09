package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContext._
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ResourceGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdResult
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.model.jsonld.RemoteContextRef.StaticContextRef
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{IncorrectRev, ResourceIsDeprecated, ResourceIsNotDeprecated, ResourceNotFound, RevisionNotFound, UnexpectedResourceSchema}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{ResourceCommand, ResourceEvent, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.{Latest, Revision}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.Json
import io.circe.syntax.{EncoderOps, KeyOps}

import java.time.Instant

class ResourcesSpec extends CatsEffectSpec with CirceLiteral with ValidateResourceFixture with ResourceInstanceFixture {

  "The Resources state machine" when {
    val epoch   = Instant.EPOCH
    val time2   = Instant.ofEpochMilli(10L)
    val subject = User("myuser", Label.unsafe("myrealm"))
    val caller  = Caller(subject, Set.empty)
    val tag     = UserTag.unsafe("mytag")

    val jsonld = JsonLdResult(myId, compacted, expanded, remoteContexts)

    val schema1 = nxv + "myschema"

    val eval: (Option[ResourceState], ResourceCommand) => IO[ResourceEvent] =
      evaluate(alwaysValidate, clock)

    "evaluating an incoming command" should {
      "create a new event from a CreateResource command" in {
        forAll(List(Latest(schemas.resources), Latest(schema1))) { schemaRef =>
          val schemaRev = Revision(schemaRef.iri, 1)
          eval(
            None,
            CreateResource(myId, projectRef, schemaRef, source, jsonld, caller, Some(tag))
          ).accepted shouldEqual
            ResourceCreated(
              myId,
              projectRef,
              schemaRev,
              projectRef,
              types,
              source,
              compacted,
              expanded,
              remoteContextRefs,
              1,
              epoch,
              subject,
              Some(tag)
            )
        }
      }

      "create a new event from a UpdateResource command" in {
        forAll(List(None -> Latest(schemas.resources), Some(Latest(schema1)) -> Latest(schema1))) {
          case (schemaOptCmd, schemaEvent) =>
            val current   = ResourceGen.currentState(myId, projectRef, source, jsonld, schemaEvent)
            val schemaRev = Revision(schemaEvent.iri, 1)

            val additionalType       = iri"https://neuroshapes.org/AnotherType"
            val newTypes             = types + additionalType
            val newSource            = source.deepMerge(Json.obj("@type" := newTypes))
            val newCompacted         = compacted.copy(obj = compacted.obj.add("types", newTypes.asJson))
            val newExpanded          = expanded.addType(additionalType)
            val newRemoteContext     = StaticContext(iri"https://bbp.epfl.ch/another-context", ContextValue.empty)
            val newRemoteContexts    = remoteContexts + (newRemoteContext.iri -> newRemoteContext)
            val newRemoteContextRefs = remoteContextRefs + StaticContextRef(iri"https://bbp.epfl.ch/another-context")
            val newJsonLd            = JsonLdResult(
              myId,
              newCompacted,
              newExpanded,
              newRemoteContexts
            )
            eval(
              Some(current),
              UpdateResource(myId, projectRef, schemaOptCmd, newSource, newJsonLd, 1, caller, Some(tag))
            ).accepted shouldEqual
              ResourceUpdated(
                myId,
                projectRef,
                schemaRev,
                projectRef,
                newTypes,
                newSource,
                newCompacted,
                newExpanded,
                newRemoteContextRefs,
                2,
                epoch,
                subject,
                Some(tag)
              )
        }
      }

      "create a new event from a TagResource command" in {
        val list = List(
          (None, Latest(schemas.resources), false),
          (None, Latest(schema1), false),
          (Some(Latest(schema1)), Latest(schema1), true)
        )
        forAll(list) { case (schemaOptCmd, schemaEvent, deprecated) =>
          val current =
            ResourceGen.currentState(myId, projectRef, source, jsonld, schemaEvent, rev = 2, deprecated = deprecated)

          eval(
            Some(current),
            TagResource(myId, projectRef, schemaOptCmd, 1, UserTag.unsafe("myTag"), 2, subject)
          ).accepted shouldEqual
            ResourceTagAdded(myId, projectRef, types, 1, UserTag.unsafe("myTag"), 3, epoch, subject)
        }
      }

      "create a new event from a DeleteResourceTag command" in {
        val list = List(
          (None, Latest(schemas.resources), false),
          (None, Latest(schema1), false),
          (Some(Latest(schema1)), Latest(schema1), true)
        )
        val tag  = UserTag.unsafe("myTag")
        forAll(list) { case (schemaOptCmd, schemaEvent, deprecated) =>
          val current =
            ResourceGen.currentState(
              myId,
              projectRef,
              source,
              jsonld,
              schemaEvent,
              rev = 2,
              deprecated = deprecated,
              tags = Tags(tag -> 1)
            )

          eval(
            Some(current),
            DeleteResourceTag(myId, projectRef, schemaOptCmd, tag, 2, subject)
          ).accepted shouldEqual
            ResourceTagDeleted(myId, projectRef, types, UserTag.unsafe("myTag"), 3, epoch, subject)
        }
      }

      "create a new event from a DeprecateResource command" in {
        val list = List(
          None                  -> Latest(schemas.resources),
          None                  -> Latest(schema1),
          Some(Latest(schema1)) -> Latest(schema1)
        )
        forAll(list) { case (schemaOptCmd, schemaEvent) =>
          val current =
            ResourceGen.currentState(myId, projectRef, source, jsonld, schemaEvent, rev = 2)

          eval(Some(current), DeprecateResource(myId, projectRef, schemaOptCmd, 2, subject)).accepted shouldEqual
            ResourceDeprecated(myId, projectRef, types, 3, epoch, subject)
        }
      }

      "create a new event from a UndeprecateResource command" in {
        val deprecatedState = ResourceGen.currentState(myId, projectRef, source, jsonld, deprecated = true)
        val undeprecateCmd  = UndeprecateResource(myId, projectRef, None, 1, subject)
        eval(deprecatedState.some, undeprecateCmd).accepted shouldEqual
          ResourceUndeprecated(myId, projectRef, types, 2, epoch, subject)
      }

      "reject with IncorrectRev" in {
        val current = ResourceGen.currentState(myId, projectRef, source, jsonld)
        val list    = List(
          current -> UpdateResource(myId, projectRef, None, source, jsonld, 2, caller, None),
          current -> TagResource(myId, projectRef, None, 1, UserTag.unsafe("tag"), 2, subject),
          current -> DeleteResourceTag(myId, projectRef, None, UserTag.unsafe("tag"), 2, subject),
          current -> DeprecateResource(myId, projectRef, None, 2, subject),
          current -> UndeprecateResource(myId, projectRef, None, 2, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(Some(state), cmd).rejected shouldEqual IncorrectRev(provided = 2, expected = 1)
        }
      }

      "reject with UnexpectedResourceSchema" in {
        val currentSchema = Latest(schema1)
        val otherSchema   = Latest(schemas.resources).some
        val current       = ResourceGen.currentState(myId, projectRef, source, jsonld, currentSchema, rev = 2)
        val commands      = List(
          UndeprecateResource(myId, projectRef, otherSchema, 2, subject),
          DeprecateResource(myId, projectRef, otherSchema, 2, subject)
        )

        forAll(commands) { cmd =>
          eval(current.some, cmd).rejectedWith[UnexpectedResourceSchema]
        }
      }

      "reject with ResourceNotFound" in {
        val list = List(
          None -> UpdateResource(myId, projectRef, None, source, jsonld, 1, caller, None),
          None -> TagResource(myId, projectRef, None, 1, UserTag.unsafe("myTag"), 1, subject),
          None -> DeprecateResource(myId, projectRef, None, 1, subject),
          None -> UndeprecateResource(myId, projectRef, None, 1, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejectedWith[ResourceNotFound]
        }
      }

      "reject with ResourceIsDeprecated" in {
        val current = ResourceGen.currentState(myId, projectRef, source, jsonld, deprecated = true)
        val list    = List(
          current -> UpdateResource(myId, projectRef, None, source, jsonld, 1, caller, None),
          current -> DeprecateResource(myId, projectRef, None, 1, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(Some(state), cmd).rejectedWith[ResourceIsDeprecated]
        }
      }

      "reject with ResourceIsNotDeprecated" in {
        val activeState    = ResourceGen.currentState(myId, projectRef, source, jsonld)
        val undeprecateCmd = UndeprecateResource(myId, projectRef, None, 1, subject)
        eval(activeState.some, undeprecateCmd).rejectedWith[ResourceIsNotDeprecated]
      }

      "reject with RevisionNotFound" in {
        val current = ResourceGen.currentState(myId, projectRef, source, jsonld)
        eval(
          Some(current),
          TagResource(myId, projectRef, None, 3, UserTag.unsafe("myTag"), 1, subject)
        ).rejected shouldEqual
          RevisionNotFound(provided = 3, current = 1)
      }
    }

    "producing next state" should {
      val schema         = Latest(schemas.resources)
      val schemaRev      = Revision(schemas.resources, 1)
      val tags           = Tags(UserTag.unsafe("a") -> 1)
      val current        = ResourceGen.currentState(myId, projectRef, source, jsonld, schema, tags)
      val compacted      = current.compacted
      val expanded       = current.expanded
      val remoteContexts = current.remoteContexts

      "create a new ResourceCreated state" in {
        next(
          None,
          ResourceCreated(
            myId,
            projectRef,
            schemaRev,
            projectRef,
            types,
            source,
            compacted,
            expanded,
            remoteContexts,
            1,
            epoch,
            subject,
            Some(tag)
          )
        ).value shouldEqual
          current.copy(
            createdAt = epoch,
            schema = schemaRev,
            createdBy = subject,
            updatedAt = epoch,
            updatedBy = subject,
            tags = Tags(tag -> schemaRev.rev)
          )

        next(
          Some(current),
          ResourceCreated(
            myId,
            projectRef,
            schemaRev,
            projectRef,
            types,
            source,
            compacted,
            expanded,
            remoteContexts,
            1,
            time2,
            subject,
            Some(tag)
          )
        ) shouldEqual None
      }

      "create a new ResourceUpdated state" in {
        val newTypes  = types + (nxv + "Other")
        val newSource = source deepMerge Json.obj("key" := "value")
        next(
          None,
          ResourceUpdated(
            myId,
            projectRef,
            schemaRev,
            projectRef,
            newTypes,
            source,
            compacted,
            expanded,
            remoteContexts,
            1,
            time2,
            subject,
            None
          )
        ) shouldEqual None

        next(
          Some(current),
          ResourceUpdated(
            myId,
            projectRef,
            schemaRev,
            projectRef,
            newTypes,
            newSource,
            compacted,
            expanded,
            remoteContexts,
            2,
            time2,
            subject,
            None
          )
        ).value shouldEqual
          current.copy(
            rev = 2,
            source = newSource,
            updatedAt = time2,
            updatedBy = subject,
            types = newTypes,
            schema = schemaRev
          )
      }

      "create new ResourceTagAdded state" in {
        val tag = UserTag.unsafe("tag")
        next(
          None,
          ResourceTagAdded(myId, projectRef, types, 1, tag, 2, time2, subject)
        ) shouldEqual None

        next(Some(current), ResourceTagAdded(myId, projectRef, types, 1, tag, 2, time2, subject)).value shouldEqual
          current.copy(rev = 2, updatedAt = time2, updatedBy = subject, tags = tags + (tag -> 1))
      }

      "create new ResourceDeprecated state" in {
        next(None, ResourceDeprecated(myId, projectRef, types, 1, time2, subject)) shouldEqual None

        next(Some(current), ResourceDeprecated(myId, projectRef, types, 2, time2, subject)).value shouldEqual
          current.copy(rev = 2, deprecated = true, updatedAt = time2, updatedBy = subject)
      }

      "create new ResourceUndeprecated state" in {
        val resourceUndeprecatedEvent = ResourceUndeprecated(myId, projectRef, types, 2, time2, subject)
        val deprecatedState           = current.copy(deprecated = true)

        next(None, resourceUndeprecatedEvent) shouldEqual None
        next(deprecatedState.some, resourceUndeprecatedEvent).value shouldEqual
          deprecatedState.copy(rev = 2, deprecated = false, updatedAt = time2, updatedBy = subject)
      }
    }
  }
}
