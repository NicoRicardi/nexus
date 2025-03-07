package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.{ExplainResult, RdfError}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.{ConversionError, RemoteContextCircularDependency, RemoteContextError, UnexpectedJsonLd, UnexpectedJsonLdContext}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdJavaApi.{ioTryOrRdfError, tryOrRdfError}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApiConfig.ErrorHandling
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context._
import com.github.jsonldjava.core.JsonLdError.Error.RECURSIVE_CONTEXT_INCLUSION
import com.github.jsonldjava.core.{Context, DocumentLoader, JsonLdError, JsonLdOptions => JsonLdJavaOptions, JsonLdProcessor}
import com.github.jsonldjava.utils.JsonUtils
import io.circe.syntax._
import io.circe.{parser, Json, JsonObject}
import org.apache.jena.query.DatasetFactory
import org.apache.jena.riot.RDFFormat.{JSONLD_EXPAND_FLAT => EXPAND}
import org.apache.jena.riot.system.ErrorHandlerFactory
import org.apache.jena.riot._
import org.apache.jena.sparql.core.DatasetGraph

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
  * Json-LD high level API implementation by Json-LD Java library
  */
final class JsonLdJavaApi(config: JsonLdApiConfig) extends JsonLdApi {

  System.setProperty(DocumentLoader.DISALLOW_REMOTE_CONTEXT_LOADING, "true")

  override private[rdf] def compact(
      input: Json,
      ctx: ContextValue
  )(implicit opts: JsonLdOptions, rcr: RemoteContextResolution): IO[JsonObject] =
    for {
      obj          <- ioTryOrRdfError(JsonUtils.fromString(input.noSpaces), "building input")
      ctxObj       <- ioTryOrRdfError(JsonUtils.fromString(ctx.toString), "building context")
      options      <- documentLoader(input, ctx.contextObj.asJson).map(toOpts)
      compacted    <- ioTryOrRdfError(JsonUtils.toString(JsonLdProcessor.compact(obj, ctxObj, options)), "compacting")
      compactedObj <- IO.fromEither(toJsonObjectOrErr(compacted))
    } yield compactedObj

  override private[rdf] def expand(
      input: Json
  )(implicit opts: JsonLdOptions, rcr: RemoteContextResolution): IO[Seq[JsonObject]] =
    explainExpand(input).map(_.value)

  override private[rdf] def explainExpand(
      input: Json
  )(implicit opts: JsonLdOptions, rcr: RemoteContextResolution): IO[ExplainResult[Seq[JsonObject]]] =
    for {
      obj            <- ioTryOrRdfError(JsonUtils.fromString(input.noSpaces), "building input")
      remoteContexts <- remoteContexts(input)
      options         = toOpts(documentLoader(remoteContexts))
      expanded       <- ioTryOrRdfError(JsonUtils.toString(JsonLdProcessor.expand(obj, options)), "expanding")
      expandedSeqObj <- IO.fromEither(toSeqJsonObjectOrErr(expanded))
    } yield ExplainResult(remoteContexts, expandedSeqObj)

  override private[rdf] def frame(
      input: Json,
      frame: Json
  )(implicit opts: JsonLdOptions, rcr: RemoteContextResolution): IO[JsonObject] =
    for {
      obj       <- ioTryOrRdfError(JsonUtils.fromString(input.noSpaces), "building input")
      ff        <- ioTryOrRdfError(JsonUtils.fromString(frame.noSpaces), "building frame")
      options   <- documentLoader(input, frame).map(toOpts)
      framed    <- ioTryOrRdfError(JsonUtils.toString(JsonLdProcessor.frame(obj, ff, options)), "framing")
      framedObj <- IO.fromEither(toJsonObjectOrErr(framed))
    } yield framedObj

  override private[rdf] def toRdf(input: Json)(implicit opts: JsonLdOptions): Either[RdfError, DatasetGraph] = {
    val c           = new JsonLDReadContext()
    c.setOptions(toOpts())
    val ds          = DatasetFactory.create
    val initBuilder = RDFParser.create
      .fromString(input.noSpaces)
      .lang(Lang.JSONLD)
      .context(c)
      .strict(config.strict)
      .checking(config.extraChecks)
      .errorHandler {
        config.errorHandling match {
          case ErrorHandling.Default   => ErrorHandlerFactory.getDefaultErrorHandler
          case ErrorHandling.Strict    => ErrorHandlerFactory.errorHandlerStrictNoLogging
          case ErrorHandling.NoWarning => ErrorHandlerFactory.errorHandlerNoWarnings
        }
      }
    val builder     = opts.base.fold(initBuilder)(base => initBuilder.base(base.toString))
    tryOrRdfError(builder.parse(ds.asDatasetGraph()), "toRdf").as(ds.asDatasetGraph())
  }

  override private[rdf] def fromRdf(
      input: DatasetGraph
  )(implicit opts: JsonLdOptions): Either[RdfError, Seq[JsonObject]] = {
    val c = new JsonLDWriteContext()
    c.setOptions(toOpts())
    for {
      expanded       <- tryOrRdfError(RDFWriter.create.format(EXPAND).source(input).context(c).asString(), "fromRdf")
      expandedSeqObj <- toSeqJsonObjectOrErr(expanded)
    } yield expandedSeqObj
  }

  override private[rdf] def context(
      value: ContextValue
  )(implicit opts: JsonLdOptions, rcr: RemoteContextResolution): IO[JsonLdContext] =
    for {
      dl     <- documentLoader(value.contextObj.asJson)
      jOpts   = toOpts(dl)
      ctx    <- IO.fromTry(Try(new Context(jOpts).parse(JsonUtils.fromString(value.toString))))
                  .adaptError { err => UnexpectedJsonLdContext(err.getMessage) }
      pm      = ctx.getPrefixes(true).asScala.toMap.map { case (k, v) => k -> iri"$v" }
      aliases = (ctx.getPrefixes(false).asScala.toMap -- pm.keySet).map { case (k, v) => k -> iri"$v" }
    } yield JsonLdContext(value, getIri(ctx, keywords.base), getIri(ctx, keywords.vocab), aliases, pm)

  private def remoteContexts(
      jsons: Json*
  )(implicit rcr: RemoteContextResolution): IO[Map[Iri, RemoteContext]] =
    jsons
      .parTraverse(rcr(_))
      .adaptError { case r: RemoteContextResolutionError => RemoteContextError(r) }
      .map(_.foldLeft(Map.empty[Iri, RemoteContext])(_ ++ _))

  private def documentLoader(remoteContexts: Map[Iri, RemoteContext]): DocumentLoader =
    remoteContexts.foldLeft(new DocumentLoader()) { case (dl, (iri, ctx)) =>
      dl.addInjectedDoc(iri.toString, ctx.value.contextObj.asJson.noSpaces)
    }

  private def documentLoader(jsons: Json*)(implicit rcr: RemoteContextResolution): IO[DocumentLoader] =
    remoteContexts(jsons: _*).map(documentLoader)

  private def toOpts(dl: DocumentLoader = new DocumentLoader)(implicit options: JsonLdOptions): JsonLdJavaOptions = {
    val opts = new JsonLdJavaOptions()
    options.base.foreach(b => opts.setBase(b.toString))
    opts.setCompactArrays(options.compactArrays)
    opts.setCompactArrays(options.compactArrays)
    opts.setProcessingMode(options.processingMode)
    opts.setProduceGeneralizedRdf(options.produceGeneralizedRdf)
    opts.setPruneBlankNodeIdentifiers(options.pruneBlankNodeIdentifiers)
    opts.setUseNativeTypes(options.useNativeTypes)
    opts.setUseRdfType(options.useRdfType)
    opts.setEmbed(options.embed)
    opts.setExplicit(options.explicit)
    opts.setOmitGraph(options.omitGraph)
    opts.setOmitDefault(options.omitDefault)
    opts.setRequireAll(options.requiredAll)
    opts.setDocumentLoader(dl)
    opts
  }

  private def getIri(ctx: Context, key: String): Option[Iri] =
    Option(ctx.get(key)).collectFirstSome {
      case str: String => str.toIri.toOption
      case _           => None
    }

  private def toJsonObjectOrErr(string: String): Either[RdfError, JsonObject] =
    for {
      json <- parser.parse(string).leftMap(err => UnexpectedJsonLd(err.getMessage()))
      obj  <- json.asObject.toRight(UnexpectedJsonLd("Expected a Json Object"))
    } yield obj

  private def toSeqJsonObjectOrErr(string: String): Either[RdfError, Seq[JsonObject]] =
    for {
      json   <- parser.parse(string).leftMap(err => UnexpectedJsonLd(err.getMessage()))
      objSeq <- json.asArray
                  .flatMap(_.foldM(Vector.empty[JsonObject])((seq, json) => json.asObject.map(seq :+ _)))
                  .toRight(UnexpectedJsonLd("Expected a sequence of Json Object"))
    } yield objSeq
}

object JsonLdJavaApi {

  /**
    * Creates an API with a config with strict values
    */
  def strict: JsonLdApi =
    new JsonLdJavaApi(
      JsonLdApiConfig(strict = true, extraChecks = true, errorHandling = ErrorHandling.Strict)
    )

  /**
    * Creates an API with a config with lenient values
    */
  def lenient: JsonLdApi =
    new JsonLdJavaApi(
      JsonLdApiConfig(strict = false, extraChecks = false, errorHandling = ErrorHandling.NoWarning)
    )

  private[rdf] def ioTryOrRdfError[A](value: => A, stage: String): IO[A] =
    IO.fromEither(tryOrRdfError(value, stage))

  private[rdf] def tryOrRdfError[A](value: => A, stage: String): Either[RdfError, A] =
    Try(value).toEither.leftMap {
      case err: JsonLdError if err.getType == RECURSIVE_CONTEXT_INCLUSION =>
        val iri = Iri(err.getMessage.replace(s"$RECURSIVE_CONTEXT_INCLUSION:", "").trim).getOrElse(iri"")
        RemoteContextCircularDependency(iri)
      case err                                                            =>
        ConversionError(err.getMessage, stage)
    }
}
