package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.PullRequestActive
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ReferenceRegistry
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.FilterByType.FilterByTypeConfig
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

import java.time.Instant

class FilterByTypeSuite extends NexusSuite {

  private val base    = iri"http://localhost"
  private val instant = Instant.now()
  private val project = ProjectRef(Label.unsafe("org"), Label.unsafe("proj"))
  private val state   = PullRequestActive(
    id = base / "id",
    project = project,
    rev = 1,
    createdAt = instant,
    createdBy = Anonymous,
    updatedAt = instant,
    updatedBy = Anonymous
  )
  private val graph   = PullRequestState.toGraphResource(state, base)

  private val registry = new ReferenceRegistry
  registry.register(FilterByType)

  def element(types: Set[Iri]): SuccessElem[GraphResource] =
    SuccessElem(
      tpe = PullRequest.entityType,
      id = base / "id",
      project = Some(project),
      instant = instant,
      offset = Offset.at(1L),
      value = graph.copy(types = types),
      rev = 1
    )

  def pipe(types: Set[Iri]): FilterByType =
    registry
      .lookupA[FilterByType.type](FilterByType.ref)
      .rightValue
      .withJsonLdConfig(FilterByTypeConfig(types).toJsonLd)
      .rightValue

  test("Do not filter elements if the expected type set is empty") {
    val elem = element(Set(iri"http://localhost/tpe1"))
    pipe(Set.empty).apply(elem).assertEquals(elem)
  }

  test("Do not filter elements if the expected and elem type set is empty") {
    val elem = element(Set.empty)
    pipe(Set.empty).apply(elem).assertEquals(elem)
  }

  test("Do not filter elements if the type intersection is not void") {
    val elem = element(Set(iri"http://localhost/tpe1", iri"http://localhost/tpe2"))
    pipe(Set(iri"http://localhost/tpe2", iri"http://localhost/tpe3")).apply(elem).assertEquals(elem)
  }

  test("Filter elements if the type intersection is void") {
    val elem = element(Set(iri"http://localhost/tpe1"))
    pipe(Set(iri"http://localhost/tpe2", iri"http://localhost/tpe3")).apply(elem).assertEquals(elem.dropped)
  }
}
