/*
 * Copyright 2020 Red Hat, Inc. and/or its affiliates.
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

package org.kie.workbench.common.dmn.client.marshaller.unmarshall.nodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;

import com.ait.lienzo.client.core.types.Point2D;
import jsinterop.base.Js;
import org.kie.workbench.common.dmn.api.definition.model.Association;
import org.kie.workbench.common.dmn.api.definition.model.AuthorityRequirement;
import org.kie.workbench.common.dmn.api.definition.model.InformationRequirement;
import org.kie.workbench.common.dmn.api.definition.model.KnowledgeRequirement;
import org.kie.workbench.common.dmn.api.property.dmn.Description;
import org.kie.workbench.common.dmn.api.property.dmn.Id;
import org.kie.workbench.common.dmn.client.marshaller.common.IdUtils;
import org.kie.workbench.common.dmn.client.marshaller.converters.dd.PointUtils;
import org.kie.workbench.common.dmn.webapp.kogito.marshaller.js.model.dc.JSIBounds;
import org.kie.workbench.common.dmn.webapp.kogito.marshaller.js.model.dc.JSIPoint;
import org.kie.workbench.common.dmn.webapp.kogito.marshaller.js.model.dmn12.JSITAssociation;
import org.kie.workbench.common.dmn.webapp.kogito.marshaller.js.model.dmn12.JSITAuthorityRequirement;
import org.kie.workbench.common.dmn.webapp.kogito.marshaller.js.model.dmn12.JSITBusinessKnowledgeModel;
import org.kie.workbench.common.dmn.webapp.kogito.marshaller.js.model.dmn12.JSITDMNElement;
import org.kie.workbench.common.dmn.webapp.kogito.marshaller.js.model.dmn12.JSITDMNElementReference;
import org.kie.workbench.common.dmn.webapp.kogito.marshaller.js.model.dmn12.JSITDecision;
import org.kie.workbench.common.dmn.webapp.kogito.marshaller.js.model.dmn12.JSITDecisionService;
import org.kie.workbench.common.dmn.webapp.kogito.marshaller.js.model.dmn12.JSITInformationRequirement;
import org.kie.workbench.common.dmn.webapp.kogito.marshaller.js.model.dmn12.JSITKnowledgeRequirement;
import org.kie.workbench.common.dmn.webapp.kogito.marshaller.js.model.dmn12.JSITKnowledgeSource;
import org.kie.workbench.common.dmn.webapp.kogito.marshaller.js.model.dmndi12.JSIDMNDiagram;
import org.kie.workbench.common.dmn.webapp.kogito.marshaller.js.model.dmndi12.JSIDMNEdge;
import org.kie.workbench.common.stunner.core.api.FactoryManager;
import org.kie.workbench.common.stunner.core.graph.Edge;
import org.kie.workbench.common.stunner.core.graph.Node;
import org.kie.workbench.common.stunner.core.graph.content.relationship.Child;
import org.kie.workbench.common.stunner.core.graph.content.view.Connection;
import org.kie.workbench.common.stunner.core.graph.content.view.ControlPoint;
import org.kie.workbench.common.stunner.core.graph.content.view.MagnetConnection;
import org.kie.workbench.common.stunner.core.graph.content.view.View;
import org.kie.workbench.common.stunner.core.graph.content.view.ViewConnector;
import org.kie.workbench.common.stunner.core.graph.impl.EdgeImpl;

import static org.kie.workbench.common.dmn.client.marshaller.common.JsInteropUtils.forEach;
import static org.kie.workbench.common.dmn.client.marshaller.converters.dd.PointUtils.upperLeftBound;
import static org.kie.workbench.common.dmn.client.marshaller.converters.dd.PointUtils.xOfBound;
import static org.kie.workbench.common.dmn.client.marshaller.converters.dd.PointUtils.yOfBound;
import static org.kie.workbench.common.stunner.core.definition.adapter.binding.BindableAdapterUtils.getDefinitionId;

@Dependent
public class NodeConnector {

    private final FactoryManager factoryManager;

    private static final double CENTRE_TOLERANCE = 1.0;

    private static final String INFO_REQ_ID = getDefinitionId(InformationRequirement.class);

    private static final String KNOWLEDGE_REQ_ID = getDefinitionId(KnowledgeRequirement.class);

    private static final String AUTH_REQ_ID = getDefinitionId(AuthorityRequirement.class);

    private static final String ASSOCIATION_ID = getDefinitionId(Association.class);

    @Inject
    public NodeConnector(final FactoryManager factoryManager) {
        this.factoryManager = factoryManager;
    }

    void connect(final JSIDMNDiagram dmnDiagram,
                 final List<JSIDMNEdge> edges,
                 final List<JSITAssociation> associations,
                 final List<NodeEntry> nodeEntries) {

        final Map<String, List<NodeEntry>> entriesById = makeNodeIndex(nodeEntries);
        final String diagramId = dmnDiagram.getId();
        final List<JSIDMNEdge> pendingEdges = new ArrayList<>(edges);

        for (final NodeEntry nodeEntry : nodeEntries) {

            final JSITDMNElement element = nodeEntry.getDmnElement();
            final Node node = nodeEntry.getNode();
            // For imported nodes, we don't have its connections
            if (nodeEntry.isIncluded()) {
                continue;
            }

            // DMN spec table 2: Requirements
            if (JSITDecision.instanceOf(element)) {
                final JSITDecision decision = Js.uncheckedCast(element);
                final List<JSITInformationRequirement> jsiInformationRequirements = decision.getInformationRequirement();
                for (int i = 0; i < jsiInformationRequirements.size(); i++) {
                    final JSITInformationRequirement ir = Js.uncheckedCast(jsiInformationRequirements.get(i));
                    connectEdgeToNodes(INFO_REQ_ID,
                                       ir,
                                       ir.getRequiredInput(),
                                       entriesById,
                                       diagramId,
                                       edges,
                                       pendingEdges,
                                       node);
                    connectEdgeToNodes(INFO_REQ_ID,
                                       ir,
                                       ir.getRequiredDecision(),
                                       entriesById,
                                       diagramId,
                                       edges,
                                       pendingEdges,
                                       node);
                }
                final List<JSITKnowledgeRequirement> jsiKnowledgeRequirements = decision.getKnowledgeRequirement();
                for (int i = 0; i < jsiKnowledgeRequirements.size(); i++) {
                    final JSITKnowledgeRequirement kr = Js.uncheckedCast(jsiKnowledgeRequirements.get(i));
                    connectEdgeToNodes(KNOWLEDGE_REQ_ID,
                                       kr,
                                       kr.getRequiredKnowledge(),
                                       entriesById,
                                       diagramId,
                                       edges,
                                       pendingEdges,
                                       node);
                }
                final List<JSITAuthorityRequirement> jsiAuthorityRequirements = decision.getAuthorityRequirement();
                for (int i = 0; i < jsiAuthorityRequirements.size(); i++) {
                    final JSITAuthorityRequirement ar = Js.uncheckedCast(jsiAuthorityRequirements.get(i));
                    connectEdgeToNodes(AUTH_REQ_ID,
                                       ar,
                                       ar.getRequiredAuthority(),
                                       entriesById,
                                       diagramId,
                                       edges,
                                       pendingEdges,
                                       node);
                }
                continue;
            }

            if (JSITBusinessKnowledgeModel.instanceOf(element)) {
                final JSITBusinessKnowledgeModel bkm = Js.uncheckedCast(element);
                final List<JSITKnowledgeRequirement> jsiKnowledgeRequirements = bkm.getKnowledgeRequirement();
                for (int i = 0; i < jsiKnowledgeRequirements.size(); i++) {
                    final JSITKnowledgeRequirement kr = Js.uncheckedCast(jsiKnowledgeRequirements.get(i));
                    connectEdgeToNodes(KNOWLEDGE_REQ_ID,
                                       kr,
                                       kr.getRequiredKnowledge(),
                                       entriesById,
                                       diagramId,
                                       edges,
                                       pendingEdges,
                                       node);
                }
                final List<JSITAuthorityRequirement> jsiAuthorityRequirements = bkm.getAuthorityRequirement();
                for (int i = 0; i < jsiAuthorityRequirements.size(); i++) {
                    final JSITAuthorityRequirement ar = Js.uncheckedCast(jsiAuthorityRequirements.get(i));
                    connectEdgeToNodes(AUTH_REQ_ID,
                                       ar,
                                       ar.getRequiredAuthority(),
                                       entriesById,
                                       diagramId,
                                       edges,
                                       pendingEdges,
                                       node);
                }
                continue;
            }

            if (JSITKnowledgeSource.instanceOf(element)) {
                final JSITKnowledgeSource ks = Js.uncheckedCast(element);
                final List<JSITAuthorityRequirement> jsiAuthorityRequirements = ks.getAuthorityRequirement();
                for (int i = 0; i < jsiAuthorityRequirements.size(); i++) {
                    final JSITAuthorityRequirement ar = Js.uncheckedCast(jsiAuthorityRequirements.get(i));
                    connectEdgeToNodes(AUTH_REQ_ID,
                                       ar,
                                       ar.getRequiredInput(),
                                       entriesById,
                                       diagramId,
                                       edges,
                                       pendingEdges,
                                       node);
                    connectEdgeToNodes(AUTH_REQ_ID,
                                       ar,
                                       ar.getRequiredDecision(),
                                       entriesById,
                                       diagramId,
                                       edges,
                                       pendingEdges,
                                       node);
                    connectEdgeToNodes(AUTH_REQ_ID,
                                       ar,
                                       ar.getRequiredAuthority(),
                                       entriesById,
                                       diagramId,
                                       edges,
                                       pendingEdges,
                                       node);
                }
                continue;
            }

            if (JSITDecisionService.instanceOf(element)) {
                final JSITDecisionService ds = Js.uncheckedCast(element);

                final List<JSITDMNElementReference> encapsulatedDecisions = ds.getEncapsulatedDecision();
                forEach(encapsulatedDecisions, er -> {
                    final String reqInputID = getId(er);
                    getNode(nodeEntry, reqInputID, entriesById)
                            .ifPresent(requiredNode -> {
                                connectDSChildEdge(node, requiredNode);
                            });
                });

                final List<JSITDMNElementReference> outputDecisions = ds.getOutputDecision();
                forEach(outputDecisions, er -> {
                    final String reqInputID = getId(er);
                    getNode(nodeEntry, reqInputID, entriesById)
                            .ifPresent(requiredNode -> {
                                connectDSChildEdge(node, requiredNode);
                            });
                });
            }
        }

        forEach(associations, association -> {

            final String sourceId = getId(association.getSourceRef());
            final String targetId = getId(association.getTargetRef());
            final List<NodeEntry> source = entriesById.get(sourceId);
            final List<NodeEntry> target = entriesById.get(targetId);
            final boolean sourcePresent = source != null && source.size() > 0;
            final boolean targetPresent = target != null && target.size() > 0;

            if (sourcePresent && targetPresent) {
                final NodeEntry sourceEntry = source.get(0);
                final NodeEntry targetEntry = target.get(0);
                final Node sourceNode = sourceEntry.getNode();
                final Node targetNode = targetEntry.getNode();

                @SuppressWarnings("unchecked")
                final Edge<View<Association>, ?> myEdge = (Edge<View<Association>, ?>) factoryManager.newElement(diagramId + "#" + association.getId(),
                                                                                                                 ASSOCIATION_ID).asEdge();

                final ViewConnector connectionContent = (ViewConnector) myEdge.getContent();
                final Id id = new Id(association.getId());
                final Description description = new Description(association.getDescription());
                final Association definition = new Association(id, description);

                connectEdge(myEdge,
                            sourceNode,
                            targetNode);

                connectionContent.setDefinition(definition);
                connectionContent.setTargetConnection(MagnetConnection.Builder.atCenter(targetNode));
                connectionContent.setSourceConnection(MagnetConnection.Builder.atCenter(sourceNode));
            }
        });
    }

    private Optional<Node> getNode(final NodeEntry decisionServiceEntry,
                                   final String internalDMNElementId,
                                   final Map<String, List<NodeEntry>> entriesById) {

        final JSIBounds decisionServiceBounds = decisionServiceEntry.getDmnShape().getBounds();

        for (final Map.Entry<String, List<NodeEntry>> entry : entriesById.entrySet()) {

            final String id = entry.getKey();
            final List<NodeEntry> entries = entry.getValue();

            if (id.contains(internalDMNElementId)) {
                for (final NodeEntry nodeEntry : entries) {
                    final JSIBounds nodeBounds = nodeEntry.getDmnShape().getBounds();

                    final boolean b = (nodeBounds.getX() + nodeBounds.getWidth()) < (decisionServiceBounds.getX() + decisionServiceBounds.getWidth());
                    final boolean b1 = nodeBounds.getX() > decisionServiceBounds.getX();
                    final boolean innerX = b1 && b;

                    final boolean b2 = (nodeBounds.getY() + nodeBounds.getHeight()) < (decisionServiceBounds.getY() + decisionServiceBounds.getHeight());
                    final boolean b3 = nodeBounds.getY() > decisionServiceBounds.getY();
                    final boolean innerY = b2 && b3;

                    if (innerX && innerY) {
                        return Optional.of(nodeEntry.getNode());
                    }
                }
            }
        }

        return Optional.empty();
    }

    private Map<String, List<NodeEntry>> makeNodeIndex(final List<NodeEntry> nodeEntries) {

        final Map<String, List<NodeEntry>> map = new HashMap<>();

        nodeEntries.forEach(nodeEntry -> {
            final String dmnElementId = nodeEntry.getDmnElement().getId();
            map.putIfAbsent(dmnElementId, new ArrayList<>());
            map.get(dmnElementId).add(nodeEntry);
        });

        return map;
    }

    /**
     * Stunner's factoryManager is only used to create Nodes that are considered part of a "Definition Set" (a collection of nodes visible to the User e.g. BPMN2 StartNode, EndNode and DMN's DecisionNode etc).
     * Relationships are not created with the factory.
     * This method specializes to connect with an Edge containing a Child relationship the target Node.
     */
    private void connectDSChildEdge(final Node dsNode,
                                    final Node requiredNode) {
        final String uuid = dsNode.getUUID() + "er" + requiredNode.getUUID();
        final Edge<Child, Node> myEdge = new EdgeImpl<>(uuid);
        myEdge.setContent(new Child());
        connectEdge(myEdge,
                    dsNode,
                    requiredNode);
    }

    private String getId(final JSITDMNElementReference er) {
        final String href = er.getHref();
        return href.contains("#") ? href.substring(href.indexOf('#') + 1) : href;
    }

    private void connectEdgeToNodes(final String connectorTypeId,
                                    final JSITDMNElement jsiDMNElement,
                                    final JSITDMNElementReference jsiDMNElementReference,
                                    final Map<String, List<NodeEntry>> entriesById,
                                    final String diagramId,
                                    final List<JSIDMNEdge> edges,
                                    final List<JSIDMNEdge> pendingEdges,
                                    final Node currentNode) {

        if (Objects.nonNull(jsiDMNElementReference) && !edges.isEmpty()) {
            final String reqInputID = getId(jsiDMNElementReference);
            final List<NodeEntry> nodeEntries = entriesById.get(reqInputID);
            final Optional<JSIDMNEdge> dmnEdgeOpt = edges.stream()
                    .filter(e -> {
                        final String localPart = e.getDmnElementRef().getLocalPart();
                        final String id = jsiDMNElement.getId();
                        return Objects.equals(localPart, id);
                    }).findFirst();

            if (nodeEntries != null && nodeEntries.size() > 0) {

                final Node requiredNode;
                final JSIDMNEdge dmnEdge;
                final String id;

                if (dmnEdgeOpt.isPresent()) {
                    dmnEdge = Js.uncheckedCast(dmnEdgeOpt.get());
                    requiredNode = getNode(dmnEdge, nodeEntries);
                    id = dmnEdge.getDmnElementRef().getLocalPart();
                    pendingEdges.remove(dmnEdge);
                } else if (edges.isEmpty()) {
                    dmnEdge = new JSIDMNEdge();
                    final JSIPoint point = new JSIPoint();
                    point.setX(0);
                    point.setY(0);
                    dmnEdge.addAllWaypoint(point, point);
                    final NodeEntry nodeEntry = nodeEntries.get(0);
                    requiredNode = nodeEntry.getNode();
                    id = nodeEntry.getDmnElement().getId();
                } else {
                    return;
                }

                final Edge myEdge = factoryManager.newElement(IdUtils.getPrefixedId(diagramId, id),
                                                              connectorTypeId).asEdge();
                final ViewConnector connectionContent = (ViewConnector) myEdge.getContent();

                connectEdge(myEdge,
                            requiredNode,
                            currentNode);

                setConnectionMagnets(myEdge, connectionContent, dmnEdge);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void connectEdge(final Edge edge,
                             final Node source,
                             final Node target) {
        edge.setSourceNode(source);
        edge.setTargetNode(target);
        source.getOutEdges().add(edge);
        target.getInEdges().add(edge);
    }

    private void setConnectionMagnets(final Edge edge,
                                      final ViewConnector connectionContent,
                                      final JSIDMNEdge jsidmnEdge) {

        final JSIDMNEdge e = Js.uncheckedCast(jsidmnEdge);
        final JSIPoint source = Js.uncheckedCast(e.getWaypoint().get(0));
        final Node<View<?>, Edge> sourceNode = edge.getSourceNode();
        if (null != sourceNode) {
            setConnectionMagnet(sourceNode,
                                source,
                                connectionContent::setSourceConnection);
        }
        final JSIPoint target = Js.uncheckedCast(e.getWaypoint().get(e.getWaypoint().size() - 1));
        final Node<View<?>, Edge> targetNode = edge.getTargetNode();
        if (null != targetNode) {
            setConnectionMagnet(targetNode,
                                target,
                                connectionContent::setTargetConnection);
        }
        if (e.getWaypoint().size() > 2) {
            connectionContent.setControlPoints(e.getWaypoint()
                                                       .subList(1, e.getWaypoint().size() - 1)
                                                       .stream()
                                                       .map(p -> ControlPoint.build(PointUtils.dmndiPointToPoint2D(p)))
                                                       .toArray(ControlPoint[]::new));
        }
    }

    private void setConnectionMagnet(final Node<View<?>, Edge> node,
                                     final JSIPoint magnetPoint,
                                     final Consumer<Connection> connectionConsumer) {
        final View<?> view = node.getContent();
        final double viewX = xOfBound(upperLeftBound(view));
        final double viewY = yOfBound(upperLeftBound(view));
        final double magnetRelativeX = magnetPoint.getX() - viewX;
        final double magnetRelativeY = magnetPoint.getY() - viewY;
        final double viewWidth = view.getBounds().getWidth();
        final double viewHeight = view.getBounds().getHeight();
        if (isCentre(magnetRelativeX,
                     magnetRelativeY,
                     viewWidth,
                     viewHeight)) {
            connectionConsumer.accept(MagnetConnection.Builder.atCenter(node));
        } else {
            connectionConsumer.accept(MagnetConnection.Builder.at(magnetRelativeX, magnetRelativeY).setAuto(true));
        }
    }

    private boolean isCentre(final double magnetRelativeX,
                             final double magnetRelativeY,
                             final double viewWidth,
                             final double viewHeight) {
        return Math.abs((viewWidth / 2) - magnetRelativeX) < CENTRE_TOLERANCE &&
                Math.abs((viewHeight / 2) - magnetRelativeY) < CENTRE_TOLERANCE;
    }

    private Node getNode(final JSIDMNEdge jsidmnEdge,
                         final List<NodeEntry> entries) {

        if (entries.size() == 1) {
            return entries.get(0).getNode();
        }

        final JSIPoint jsiSource = Js.uncheckedCast(jsidmnEdge.getWaypoint().get(0));
        final Point2D source = new Point2D(jsiSource.getX(), jsiSource.getY());
        final Map<Point2D, NodeEntry> entriesByPoint2D = new HashMap<>();

        for (final NodeEntry entry : entries) {
            final JSIBounds bounds = entry.getDmnShape().getBounds();
            final double centerX = bounds.getX() + (bounds.getWidth() / 2);
            final double centerY = bounds.getY() + (bounds.getHeight() / 2);
            entriesByPoint2D.put(new Point2D(centerX, centerY), entry);
        }

        final Point2D nearest = Collections.min(entriesByPoint2D.keySet(), (point1, point2) -> {
            final Double distance1 = source.distance(point1);
            final Double distance2 = source.distance(point2);
            return distance1.compareTo(distance2);
        });

        return entriesByPoint2D.get(nearest).getNode();
    }
}