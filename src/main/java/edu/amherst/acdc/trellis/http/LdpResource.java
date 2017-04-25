/*
 * Copyright Amherst College
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.amherst.acdc.trellis.http;

import static edu.amherst.acdc.trellis.http.RdfMediaType.APPLICATION_LD_JSON;
import static edu.amherst.acdc.trellis.http.RdfMediaType.APPLICATION_N_TRIPLES;
import static edu.amherst.acdc.trellis.http.RdfMediaType.APPLICATION_SPARQL_UPDATE;
import static edu.amherst.acdc.trellis.http.RdfMediaType.TEXT_TURTLE;
import static edu.amherst.acdc.trellis.http.RdfMediaType.VARIANTS;
import static edu.amherst.acdc.trellis.spi.ConstraintService.ldpResourceTypes;
import static java.util.Date.from;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Stream.concat;
import static java.util.stream.Stream.empty;
import static java.util.stream.Stream.of;
import static javax.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM;
import static javax.ws.rs.core.Response.Status.GONE;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static javax.ws.rs.core.UriBuilder.fromUri;
import static org.apache.commons.codec.digest.DigestUtils.md5Hex;
import static org.apache.commons.rdf.api.RDFSyntax.TURTLE;
import static org.apache.commons.rdf.api.RDFSyntax.RDFA_HTML;
import static org.slf4j.LoggerFactory.getLogger;

import com.codahale.metrics.annotation.Timed;

import edu.amherst.acdc.trellis.api.Datastream;
import edu.amherst.acdc.trellis.spi.DatastreamService;
import edu.amherst.acdc.trellis.spi.ResourceService;
import edu.amherst.acdc.trellis.spi.SerializationService;
import edu.amherst.acdc.trellis.vocabulary.LDP;
import edu.amherst.acdc.trellis.vocabulary.OA;
import edu.amherst.acdc.trellis.vocabulary.Trellis;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Variant;

import org.apache.commons.rdf.api.IRI;
import org.apache.commons.rdf.api.RDF;
import org.apache.commons.rdf.api.RDFSyntax;
import org.slf4j.Logger;

/**
 * @author acoburn
 */
@Path("{path: .+}")
@Produces({TEXT_TURTLE, APPLICATION_LD_JSON, APPLICATION_N_TRIPLES})
public class LdpResource {

    private static final Logger LOGGER = getLogger(LdpResource.class);

    private static final RDF rdf = ServiceLoader.load(RDF.class).iterator().next();

    private final ResourceService resourceService;
    private final SerializationService serializationService;
    private final DatastreamService datastreamService;

    /**
     * Create a LdpResource
     * @param resourceService the resource service
     * @param serializationService the serialization service
     */
    public LdpResource(final ResourceService resourceService, final SerializationService serializationService,
            final DatastreamService datastreamService) {
        this.resourceService = resourceService;
        this.serializationService = serializationService;
        this.datastreamService = datastreamService;
    }

    /**
     * Perform a GET operation on an LDP Resource
     * @param path the path
     * @param headers the headers
     * @return the response
     */
    @GET
    @Timed
    public Response getResource(@PathParam("path") final String path, @Context final HttpHeaders headers) {
        // can this go somewhere more central?
        if (path.endsWith("/")) {
            return Response.seeOther(fromUri(stripSlash(path)).build()).build();
        }

        final String identifier = "trellis:" + path;
        final Optional<RDFSyntax> syntax = getRdfSyntax(headers.getAcceptableMediaTypes());

        // TODO should also support timegates...
        return resourceService.get(rdf.createIRI(identifier)).map(res -> {
            if (res.getTypes().anyMatch(Trellis.DeletedResource::equals)) {
                // TODO add Mementos
                return Response.status(GONE);
            }

            final Response.ResponseBuilder builder = Response.ok();

            // Standard HTTP Headers
            builder.lastModified(from(res.getModified()));
            builder.variants(VARIANTS);
            builder.header("Vary", "Prefer");
            syntax.map(s -> s.mediaType).ifPresent(builder::type);

            // Add LDP-required headers
            final IRI model = res.getDatastream().isPresent() && syntax.isPresent() ?
                    LDP.RDFSource : res.getInteractionModel();
            concat(of(model), ldpResourceTypes(model)).forEach(type -> {
                builder.link(type.getIRIString(), "type");
                if (LDP.Container.equals(type)) {
                    builder.header("Accept-Post", VARIANTS.stream().map(Variant::getMediaType)
                            .map(mt -> mt.getType() + "/" + mt.getSubtype()).collect(joining(",")));
                } else if (LDP.RDFSource.equals(type)) {
                    builder.header("Accept-Patch", APPLICATION_SPARQL_UPDATE);
                }
            });

            // Add NonRDFSource-related "describe*" link headers
            res.getDatastream().ifPresent(ds -> {
                if (syntax.isPresent()) {
                    // TODO make this identifier opaque
                    builder.link(identifier + "#description", "canonical");
                    builder.link(identifier, "describes");
                } else {
                    builder.link(identifier, "canonical");
                    builder.link(identifier + "#description", "describedby");
                    builder.type(ds.getMimeType().orElse(APPLICATION_OCTET_STREAM));
                }
            });

            // Link headers from User data
            res.getTypes().map(IRI::getIRIString).forEach(type -> builder.link(type, "type"));
            res.getInbox().map(IRI::getIRIString).ifPresent(inbox -> builder.link(inbox, "inbox"));
            res.getAnnotationService().map(IRI::getIRIString).ifPresent(svc ->
                    builder.link(svc, OA.annotationService.getIRIString()));

            // NonRDFSources get strong ETags
            if (res.getDatastream().isPresent() && !syntax.isPresent()) {
                builder.tag(md5Hex(res.getDatastream().map(Datastream::getModified)
                            .map(Instant::toString).get() + identifier));
                final IRI dsid = res.getDatastream().map(Datastream::getIdentifier).get();
                final InputStream datastream = datastreamService.getResolver(dsid).flatMap(svc -> svc.getContent(dsid))
                    .orElseThrow(() ->
                        new WebApplicationException("Could not load datastream resolver for " + dsid.getIRIString()));
                builder.entity(datastream);
            } else {
                // TODO configure prefer headers
                builder.header("Preference-Applied", "return=representation");
                builder.tag(new EntityTag(md5Hex(res.getModified().toString() + identifier + syntax
                            .map(RDFSyntax::toString).orElse("")), false));
                if (syntax.get().equals(RDFA_HTML)) {
                    builder.entity("<html><head><title>Title</title></head><body>Some HTML!</body></html>");
                } else {
                    builder.entity(new RdfStreamer(serializationService, res, syntax.get()));
                }
            }

            // TODO add acl header, if in effect
            // add Memento headers
            // res.getMementos().forEach(range -> {
            //     builder.header(...);
            // });
            // TODO check cache control headers

            LOGGER.info("id: {}, format: {}", identifier, syntax.orElse(TURTLE).toString());

            return builder;
        }).orElse(Response.status(NOT_FOUND)).build();
    }

    private static Function<MediaType, Stream<RDFSyntax>> getSyntax = type -> {
        final Optional<RDFSyntax> syntax = VARIANTS.stream().map(Variant::getMediaType).filter(type::isCompatible)
            .findFirst().map(MediaType::toString).flatMap(RDFSyntax::byMediaType);
        return syntax.isPresent() ? of(syntax.get()) : empty();
    };

    private static Optional<RDFSyntax> getRdfSyntax(final List<MediaType> types) {
        return types.stream().flatMap(getSyntax).findFirst();
    }

    private static String stripSlash(final String path) {
        return path.endsWith("/") ? stripSlash(path.substring(0, path.length() - 1)) : path;
    }
}