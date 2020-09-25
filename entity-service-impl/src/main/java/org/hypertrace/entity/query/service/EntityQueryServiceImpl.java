package org.hypertrace.entity.query.service;

import static java.util.stream.Collectors.toUnmodifiableMap;
import static org.hypertrace.entity.service.constants.EntityCollectionConstants.ENTITY_LABELS_COLLECTION;
import static org.hypertrace.entity.service.constants.EntityCollectionConstants.RAW_ENTITIES_COLLECTION;

import com.google.protobuf.Descriptors;
import com.google.protobuf.GeneratedMessageV3;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.ServiceException;
import com.typesafe.config.Config;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hypertrace.core.documentstore.Collection;
import org.hypertrace.core.documentstore.Datastore;
import org.hypertrace.core.documentstore.Document;
import org.hypertrace.core.documentstore.Filter;
import org.hypertrace.core.documentstore.JSONDocument;
import org.hypertrace.core.documentstore.SingleValueKey;
import org.hypertrace.core.grpcutils.context.RequestContext;
import org.hypertrace.entity.data.service.v1.AttributeValue;
import org.hypertrace.entity.data.service.v1.Entity;
import org.hypertrace.entity.data.service.v1.EntityLabel;
import org.hypertrace.entity.data.service.v1.Query;
import org.hypertrace.entity.query.service.v1.ColumnIdentifier;
import org.hypertrace.entity.query.service.v1.ColumnMetadata;
import org.hypertrace.entity.query.service.v1.EntityQueryRequest;
import org.hypertrace.entity.query.service.v1.EntityQueryServiceGrpc.EntityQueryServiceImplBase;
import org.hypertrace.entity.query.service.v1.EntityUpdateRequest;
import org.hypertrace.entity.query.service.v1.Expression;
import org.hypertrace.entity.query.service.v1.Expression.ValueCase;
import org.hypertrace.entity.query.service.v1.ResultSetChunk;
import org.hypertrace.entity.query.service.v1.ResultSetMetadata;
import org.hypertrace.entity.query.service.v1.Row;
import org.hypertrace.entity.query.service.v1.SetAttribute;
import org.hypertrace.entity.query.service.v1.Value;
import org.hypertrace.entity.query.service.v1.ValueType;
import org.hypertrace.entity.service.constants.EntityServiceConstants;
import org.hypertrace.entity.service.util.DocStoreConverter;
import org.hypertrace.entity.service.util.DocStoreJsonFormat;
import org.hypertrace.entity.service.util.DocStoreJsonFormat.Parser;
import org.hypertrace.entity.service.util.StringUtils;
import org.hypertrace.entity.service.util.UUIDGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EntityQueryServiceImpl extends EntityQueryServiceImplBase {

  private static final Logger LOG = LoggerFactory.getLogger(EntityQueryServiceImpl.class);
  private static final String ATTRIBUTE_MAP_CONFIG_PATH = "entity.service.attributeMap";
  private static final Parser PARSER = DocStoreJsonFormat.parser().ignoringUnknownFields();

  private final Collection entitiesCollection;
  private final Collection entityLabelsCollection;
  private final Map<String, Map<String, String>> attrNameToEDSAttrMap;

  public EntityQueryServiceImpl(Datastore datastore, Config config) {
    this(
        datastore.getCollection(RAW_ENTITIES_COLLECTION),
        datastore.getCollection(ENTITY_LABELS_COLLECTION),
        config.getConfigList(ATTRIBUTE_MAP_CONFIG_PATH)
            .stream()
            .collect(toUnmodifiableMap(
                conf -> conf.getString("scope"),
                conf -> Map.of(conf.getString("name"), conf.getString("subDocPath")),
                (map1, map2) -> {
                  Map<String, String> map = new HashMap<>();
                  map.putAll(map1);
                  map.putAll(map2);
                  return map;
                }
            )));
  }

  public EntityQueryServiceImpl(Collection entitiesCollection, Collection entityLabelsCollection,
      Map<String, Map<String, String>> attrNameToEDSAttrMap) {
    this.entitiesCollection = entitiesCollection;
    this.entityLabelsCollection = entityLabelsCollection;
    this.attrNameToEDSAttrMap = attrNameToEDSAttrMap;
  }

  @Override
  public void execute(EntityQueryRequest request, StreamObserver<ResultSetChunk> responseObserver) {
    Optional<String> tenantId = RequestContext.CURRENT.get().getTenantId();
    if (tenantId.isEmpty()) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

    //TODO: Optimize this later. For now converting to EDS Query and then again to DocStore Query.
    Query query = EntityQueryConverter
        .convertToEDSQuery(request, attrNameToEDSAttrMap.get(request.getEntityType()));
    Iterator<Document> documentIterator = entitiesCollection.search(
        DocStoreConverter.transform(tenantId.get(), query));
    List<Entity> entities = convertDocsToEntities(documentIterator);

    //Build result
    //TODO : chunk response. For now sending everything in one chunk
    responseObserver.onNext(convertEntitiesToResultSetChunk(
        entities,
        request.getSelectionList(),
        attrNameToEDSAttrMap.get(request.getEntityType())));
    responseObserver.onCompleted();
  }

  private List<Entity> convertDocsToEntities(Iterator<Document> documentIterator) {
    List<Entity> entities = new ArrayList<>();
    while (documentIterator.hasNext()) {
      Document next = documentIterator.next();
      Entity.Builder builder = Entity.newBuilder();
      try {
        PARSER.merge(next.toJson(), builder);
      } catch (InvalidProtocolBufferException e) {
        LOG.error("Could not deserialize the document into an entity.", e);
      }
      entities.add(builder.build());
    }
    return entities;
  }

  private ResultSetChunk convertEntitiesToResultSetChunk(
      List<Entity> entities,
      List<Expression> selections,
      Map<String, String> attributeFqnMapping) {

    ResultSetChunk.Builder resultBuilder = ResultSetChunk.newBuilder();
    //Build metadata
    resultBuilder.setResultSetMetadata(ResultSetMetadata.newBuilder()
        .addAllColumnMetadata(
            () -> selections.stream().map(Expression::getColumnIdentifier).map(
                ColumnIdentifier::getColumnName)
                .map(s -> ColumnMetadata.newBuilder().setColumnName(s).build()).iterator())
        .build());
    //Build data
    resultBuilder.addAllRow(() -> entities.stream().map(
        entity -> convertToEntityQueryResult(entity, selections, attributeFqnMapping)).iterator());

    return resultBuilder.build();
  }

  private Row convertToEntityQueryResult(
      Entity entity, List<Expression> selections, Map<String, String> egsToEdsAttrMapping) {
    Row.Builder result = Row.newBuilder();
    selections.stream()
        .filter(expression -> expression.getValueCase() == ValueCase.COLUMNIDENTIFIER)
        .forEach(expression -> {
          String columnName = expression.getColumnIdentifier().getColumnName();
          String edsSubDocPath = egsToEdsAttrMapping.get(columnName);
          if (edsSubDocPath != null) {
            //Map the attr name to corresponding Attribute Key in EDS and get the EDS AttributeValue
            if (edsSubDocPath.equals(EntityServiceConstants.ENTITY_ID)) {
              result.addColumn(Value.newBuilder()
                  .setValueType(ValueType.STRING)
                  .setString(entity.getEntityId())
                  .build());
            } else if (edsSubDocPath.equals(EntityServiceConstants.ENTITY_NAME)) {
              result.addColumn(Value.newBuilder()
                  .setValueType(ValueType.STRING)
                  .setString(entity.getEntityName())
                  .build());
            } else if (edsSubDocPath.startsWith("attributes.")) {
              //Convert EDS AttributeValue to Gateway Value
              AttributeValue attributeValue = entity.getAttributesMap()
                  .get(edsSubDocPath.split("\\.")[1]);
              result.addColumn(
                  EntityQueryConverter.convertAttributeValueToQueryValue(attributeValue));
            }
          } else {
            LOG.warn("columnName {} missing in attrNameToEDSAttrMap", columnName);
            result.addColumn(Value.getDefaultInstance());
          }
        });
    return result.build();
  }

  @Override
  public void update(EntityUpdateRequest request, StreamObserver<ResultSetChunk> responseObserver) {
    // Validations
    Optional<String> tenantId = RequestContext.CURRENT.get().getTenantId();
    if (tenantId.isEmpty()) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }
    if (StringUtils.isEmpty(request.getEntityType())) {
      responseObserver.onError(new ServiceException("Entity type is missing in the request."));
      return;
    }
    if (request.getEntityIdsCount() == 0) {
      responseObserver.onError(new ServiceException("Entity IDs are missing in the request."));
    }
    if (!request.hasOperation()) {
      responseObserver.onError(new ServiceException("Operation is missing in the request."));
    }

    try {
      // Execute the update
      Map<String, String> attributeFqnMap = attrNameToEDSAttrMap.get(request.getEntityType());
      doUpdate(request, attributeFqnMap, tenantId.get());

      // Finally return the selections
      Query entitiesQuery = Query.newBuilder().addAllEntityId(request.getEntityIdsList()).build();
      Iterator<Document> documentIterator = entitiesCollection.search(
          DocStoreConverter.transform(tenantId.get(), entitiesQuery));
      List<Entity> entities = convertDocsToEntities(documentIterator);
      responseObserver.onNext(convertEntitiesToResultSetChunk(
          entities,
          request.getSelectionList(),
          attrNameToEDSAttrMap.get(request.getEntityType())));
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver
          .onError(new ServiceException("Error occurred while executing " + request, e));
    }
  }

  private void doUpdate(EntityUpdateRequest request, Map<String, String> attributeFqnMap,
      String tenantId) throws IOException {
    if (request.getOperation().hasSetAttribute()) {
      SetAttribute setAttribute = request.getOperation().getSetAttribute();
      String attributeFqn = setAttribute.getAttribute().getColumnName();
      if (!attributeFqnMap.containsKey(attributeFqn)) {
        throw new IllegalArgumentException("Unknown attribute FQN " + attributeFqn);
      }
      String subDocPath = attributeFqnMap.get(attributeFqn);
      String jsonValue = DocStoreJsonFormat.printer().print(setAttribute.getValue());

      for (String entityId : request.getEntityIdsList()) {
        SingleValueKey key = new SingleValueKey(tenantId, entityId);
        // TODO better error reporting once doc store exposes the,
        if (!entitiesCollection.updateSubDoc(
            key, subDocPath, new JSONDocument(jsonValue))) {
          LOG.warn("Failed to update entity {}, subDocPath {}, with new doc {}.", key, subDocPath,
              jsonValue);
        }
      }
    }
  }

  @Override
  public void createEntityLabel(EntityLabel request, io.grpc.stub.StreamObserver<EntityLabel> responseObserver) {
    Optional<String> tenantId = RequestContext.CURRENT.get().getTenantId();
    if (tenantId.isEmpty()) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

    String entityLabelId = generateEntityLabelId(tenantId.get(), request.getName());
    EntityLabel entityLabel = EntityLabel.newBuilder(request)
        .setTenantId(tenantId.get())
        .setId(entityLabelId)
        .build();

    try {
      Document document = convertProtoMessageToDocument(entityLabel);
      entityLabelsCollection.upsert(new SingleValueKey(tenantId.get(), entityLabelId), document);
      // Will also invoke the sending of the object in the response
      searchByIdAndStreamSingleResponse(tenantId.get(), entityLabelId,
          entityLabelsCollection, EntityLabel.newBuilder(), responseObserver);
    } catch (IOException e) {
      responseObserver.onError(new RuntimeException("Could not create entity label.", e));
    }

    //TODO: Optimize this later. For now converting to EDS Query and then again to DocStore Query.
//    Query query = EntityQueryConverter
//        .convertToEDSQuery(request, attrNameToEDSAttrMap.get(request.getEntityType()));
//    Iterator<Document> documentIterator = entitiesCollection.search(
//        DocStoreConverter.transform(tenantId.get(), query));
//    List<Entity> entities = convertDocsToEntities(documentIterator);

    //Build result
    //TODO : chunk response. For now sending everything in one chunk
//    responseObserver.onNext();
//    responseObserver.onCompleted();
  }

  @Override
  public void getEntityLabel(org.hypertrace.entity.data.service.v1.EntityLabelByIdRequest request, io.grpc.stub.StreamObserver<org.hypertrace.entity.data.service.v1.EntityLabel> responseObserver) {
    Optional<String> tenantId = RequestContext.CURRENT.get().getTenantId();
    if (tenantId.isEmpty()) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

//    String entityLabelId = generateEntityLabelId(tenantId.get(), request.getName());
//    EntityLabel entityLabel = EntityLabel.newBuilder(request)
//        .setTenantId(tenantId.get())
//        .setId(entityLabelId)
//        .build();
    searchByIdAndStreamSingleResponse(tenantId.get(), request.getId(),
        entityLabelsCollection, EntityLabel.newBuilder(), responseObserver);

//    try {
////      Document document = convertProtoMessageToDocument(entityLabel);
////      entityLabelsCollection.upsert(new SingleValueKey(tenantId.get(), entityLabelId), document);
//      // Will also invoke the sending of the object in the response
//      searchByIdAndStreamSingleResponse(tenantId.get(), request.getId(),
//          entityLabelsCollection, EntityLabel.newBuilder(), responseObserver);
//    } catch (IOException e) {
//      responseObserver.onError(new RuntimeException("Could not create entity label.", e));
//    }
  }

  @Override
  public void updateEntityLabel(org.hypertrace.entity.data.service.v1.EntityLabel request, io.grpc.stub.StreamObserver<org.hypertrace.entity.data.service.v1.EntityLabel> responseObserver) {
    super.updateEntityLabel(request, responseObserver);
  }

  @Override
  public void getAllEntityLabels(org.hypertrace.entity.data.service.v1.Empty request, io.grpc.stub.StreamObserver<org.hypertrace.entity.data.service.v1.EntityLabel> responseObserver) {
    Optional<String> tenantId = RequestContext.CURRENT.get().getTenantId();
    if (tenantId.isEmpty()) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

//    String entityLabelId = generateEntityLabelId(tenantId.get(), request.getName());
//    EntityLabel entityLabel = EntityLabel.newBuilder(request)
//        .setTenantId(tenantId.get())
//        .setId(entityLabelId)
//        .build();
    searchAndStreamEntityLabels(tenantId.get(), entityLabelsCollection, EntityLabel.newBuilder(),
        responseObserver);
//    searchByIdAndStreamSingleResponse(tenantId.get(), request.getId(),
//        entityLabelsCollection, EntityLabel.newBuilder(), responseObserver);
  }

  @Override
  public void addEntityLabelToEntity(org.hypertrace.entity.query.service.v1.EntityIdAndLabelId request, io.grpc.stub.StreamObserver<org.hypertrace.entity.data.service.v1.Entity> responseObserver) {
    super.addEntityLabelToEntity(request, responseObserver);
  }

  @Override
  public void removeEntityLabelFromEntity(org.hypertrace.entity.query.service.v1.EntityIdAndLabelId request, io.grpc.stub.StreamObserver<org.hypertrace.entity.data.service.v1.Entity> responseObserver) {
    super.removeEntityLabelFromEntity(request, responseObserver);
  }

  @Override
  public void getEntitiesByLabel(org.hypertrace.entity.data.service.v1.Empty request, io.grpc.stub.StreamObserver<org.hypertrace.entity.query.service.v1.EntitiesByLabel> responseObserver) {
    super.getEntitiesByLabel(request, responseObserver);
  }

  private String generateEntityLabelId(String tenantId, String labelName) {
    // TODO: Not considering Label attributes such as tenant id and name for now.
    return UUIDGenerator.getRandomUUID();
  }

  // TODO: Copied from EntityDataServiceImpl
  private <T extends GeneratedMessageV3> JSONDocument convertProtoMessageToDocument(T message)
      throws IOException {
    try {
      return DocStoreConverter.transform(message);
    } catch (IOException e) {
      LOG.error("Could not covert the attributes into JSON doc.", e);
      throw e;
    }
  }

  // TODO: Copied from EntityDataServiceImpl. Should make this generic and stop using entity or entities as variable names
  private <T extends Message> void searchByIdAndStreamSingleResponse(
      String tenantId, String entityLabelId, Collection collection, Message.Builder builder,
      StreamObserver<T> responseObserver) {
    org.hypertrace.core.documentstore.Query query = new org.hypertrace.core.documentstore.Query();
    String docId = new SingleValueKey(tenantId, entityLabelId).toString();
    query.setFilter(new Filter(Filter.Op.EQ, EntityServiceConstants.ID, docId));

    Iterator<Document> result = collection.search(query);
    List<T> entities = new ArrayList<>();
    while (result.hasNext()) {
      Document next = result.next();
      Message.Builder b = builder.clone();
      try {
        PARSER.merge(next.toJson(), b);

//        // Populate the tenant id field with the tenant id that's received for backward
//        // compatibility.
//        Descriptors.FieldDescriptor fieldDescriptor =
//            b.getDescriptorForType().findFieldByName("tenant_id");
//        if (fieldDescriptor != null) {
//          b.setField(fieldDescriptor, tenantId);
//        }
      } catch (InvalidProtocolBufferException e) {
        LOG.error("Could not deserialize the document into an entity label.", e);
      }

      entities.add((T) b.build());
    }

    if (LOG.isDebugEnabled()) {
      LOG.debug("MongoDB query has returned the result: {}", entities);
    }

    if (entities.size() == 1) {
      responseObserver.onNext(entities.get(0));
      responseObserver.onCompleted();
    } else if (entities.size() > 1) {
      responseObserver.onError(
          new IllegalStateException("Multiple entities with same id are found."));
    } else {
      // When there is no result, we should return the default instance, which is a way
      // of saying it's null.
      //TODO : Not convinced with the default instance
      responseObserver.onNext((T) builder.build());
      responseObserver.onCompleted();
    }
  }

  private <T extends Message> void searchAndStreamEntityLabels(
      String tenantId, Collection collection, Message.Builder builder,
      StreamObserver<T> responseObserver) {
    org.hypertrace.core.documentstore.Query query = new org.hypertrace.core.documentstore.Query();
    //String docId = new SingleValueKey(tenantId, entityLabelId).toString();
    query.setFilter(new Filter(Filter.Op.EQ, "tenant_id", tenantId));

    Iterator<Document> result = collection.search(query);
    //List<T> entities = new ArrayList<>();
    while (result.hasNext()) {
      Document next = result.next();
      Message.Builder b = builder.clone();
      try {
        PARSER.merge(next.toJson(), b);

        responseObserver.onNext((T) b.build());
//        // Populate the tenant id field with the tenant id that's received for backward
//        // compatibility.
//        Descriptors.FieldDescriptor fieldDescriptor =
//            b.getDescriptorForType().findFieldByName("tenant_id");
//        if (fieldDescriptor != null) {
//          b.setField(fieldDescriptor, tenantId);
//        }
      } catch (InvalidProtocolBufferException e) {
        LOG.error("Could not deserialize the document into an entity label.", e);
      }

      //entities.add((T) b.build());
    }

    responseObserver.onCompleted();

//    if (LOG.isDebugEnabled()) {
//      LOG.debug("MongoDB query has returned the result: {}", entities);
//    }

//    if (entities.size() == 1) {
//      responseObserver.onNext(entities.get(0));
//      responseObserver.onCompleted();
//    } else if (entities.size() > 1) {
//      responseObserver.onError(
//          new IllegalStateException("Multiple entities with same id are found."));
//    } else {
//      // When there is no result, we should return the default instance, which is a way
//      // of saying it's null.
//      //TODO : Not convinced with the default instance
//      responseObserver.onNext((T) builder.build());
//      responseObserver.onCompleted();
//    }
  }
}
