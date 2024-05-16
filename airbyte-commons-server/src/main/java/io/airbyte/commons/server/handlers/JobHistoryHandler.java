/*
 * Copyright (c) 2020-2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.commons.server.handlers;

import static io.airbyte.commons.server.handlers.helpers.StatsAggregationHelper.hydrateWithStats;
import static io.airbyte.featureflag.ContextKt.ANONYMOUS;
import static io.airbyte.persistence.job.models.Job.SYNC_REPLICATION_TYPES;

import com.google.common.base.Preconditions;
import io.airbyte.api.model.generated.AttemptInfoRead;
import io.airbyte.api.model.generated.AttemptNormalizationStatusReadList;
import io.airbyte.api.model.generated.ConnectionIdRequestBody;
import io.airbyte.api.model.generated.ConnectionRead;
import io.airbyte.api.model.generated.ConnectionSyncProgressReadItem;
import io.airbyte.api.model.generated.DestinationDefinitionIdRequestBody;
import io.airbyte.api.model.generated.DestinationDefinitionRead;
import io.airbyte.api.model.generated.DestinationIdRequestBody;
import io.airbyte.api.model.generated.DestinationRead;
import io.airbyte.api.model.generated.JobDebugInfoRead;
import io.airbyte.api.model.generated.JobDebugRead;
import io.airbyte.api.model.generated.JobIdRequestBody;
import io.airbyte.api.model.generated.JobInfoLightRead;
import io.airbyte.api.model.generated.JobInfoRead;
import io.airbyte.api.model.generated.JobListForWorkspacesRequestBody;
import io.airbyte.api.model.generated.JobListRequestBody;
import io.airbyte.api.model.generated.JobOptionalRead;
import io.airbyte.api.model.generated.JobRead;
import io.airbyte.api.model.generated.JobReadList;
import io.airbyte.api.model.generated.JobWithAttemptsRead;
import io.airbyte.api.model.generated.SourceDefinitionIdRequestBody;
import io.airbyte.api.model.generated.SourceDefinitionRead;
import io.airbyte.api.model.generated.SourceIdRequestBody;
import io.airbyte.api.model.generated.SourceRead;
import io.airbyte.commons.enums.Enums;
import io.airbyte.commons.server.converters.ApiPojoConverters;
import io.airbyte.commons.server.converters.JobConverter;
import io.airbyte.commons.server.converters.WorkflowStateConverter;
import io.airbyte.commons.temporal.TemporalClient;
import io.airbyte.commons.version.AirbyteVersion;
import io.airbyte.config.Configs.WorkerEnvironment;
import io.airbyte.config.JobConfig;
import io.airbyte.config.JobConfig.ConfigType;
import io.airbyte.config.JobConfigProxy;
import io.airbyte.config.StandardSync;
import io.airbyte.config.helpers.LogConfigs;
import io.airbyte.config.persistence.ConfigNotFoundException;
import io.airbyte.data.services.ConnectionService;
import io.airbyte.featureflag.FeatureFlagClient;
import io.airbyte.featureflag.HydrateAggregatedStats;
import io.airbyte.featureflag.Workspace;
import io.airbyte.persistence.job.JobPersistence;
import io.airbyte.persistence.job.models.Job;
import io.airbyte.persistence.job.models.JobStatus;
import io.airbyte.persistence.job.models.JobStatusSummary;
import io.airbyte.protocol.models.ConfiguredAirbyteStream;
import io.airbyte.protocol.models.SyncMode;
import io.airbyte.validation.json.JsonValidationException;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * JobHistoryHandler. Javadocs suppressed because api docs should be used as source of truth.
 */
@Singleton
@Slf4j
public class JobHistoryHandler {

  private final ConnectionService connectionService;
  private final SourceHandler sourceHandler;
  private final DestinationHandler destinationHandler;
  private final SourceDefinitionsHandler sourceDefinitionsHandler;
  private final DestinationDefinitionsHandler destinationDefinitionsHandler;
  public static final int DEFAULT_PAGE_SIZE = 200;
  private final JobPersistence jobPersistence;
  private final JobConverter jobConverter;
  private final WorkflowStateConverter workflowStateConverter;
  private final AirbyteVersion airbyteVersion;
  private final TemporalClient temporalClient;
  private final FeatureFlagClient featureFlagClient;

  public JobHistoryHandler(final JobPersistence jobPersistence,
                           final WorkerEnvironment workerEnvironment,
                           final LogConfigs logConfigs,
                           final ConnectionService connectionService,
                           final SourceHandler sourceHandler,
                           final SourceDefinitionsHandler sourceDefinitionsHandler,
                           final DestinationHandler destinationHandler,
                           final DestinationDefinitionsHandler destinationDefinitionsHandler,
                           final AirbyteVersion airbyteVersion,
                           final TemporalClient temporalClient,
                           final FeatureFlagClient featureFlagClient) {
    this.featureFlagClient = featureFlagClient;
    jobConverter = new JobConverter(workerEnvironment, logConfigs, featureFlagClient);
    workflowStateConverter = new WorkflowStateConverter();
    this.jobPersistence = jobPersistence;
    this.connectionService = connectionService;
    this.sourceHandler = sourceHandler;
    this.sourceDefinitionsHandler = sourceDefinitionsHandler;
    this.destinationHandler = destinationHandler;
    this.destinationDefinitionsHandler = destinationDefinitionsHandler;
    this.airbyteVersion = airbyteVersion;
    this.temporalClient = temporalClient;
  }

  @Deprecated(forRemoval = true)
  public JobHistoryHandler(final JobPersistence jobPersistence,
                           final WorkerEnvironment workerEnvironment,
                           final LogConfigs logConfigs,
                           final ConnectionService connectionService,
                           final SourceHandler sourceHandler,
                           final SourceDefinitionsHandler sourceDefinitionsHandler,
                           final DestinationHandler destinationHandler,
                           final DestinationDefinitionsHandler destinationDefinitionsHandler,
                           final AirbyteVersion airbyteVersion,
                           final FeatureFlagClient featureFlagClient) {
    this(jobPersistence, workerEnvironment, logConfigs, connectionService, sourceHandler, sourceDefinitionsHandler, destinationHandler,
        destinationDefinitionsHandler, airbyteVersion, null, featureFlagClient);
  }

  @SuppressWarnings("UnstableApiUsage")
  public JobReadList listJobsFor(final JobListRequestBody request) throws IOException {
    Preconditions.checkNotNull(request.getConfigTypes(), "configType cannot be null.");
    Preconditions.checkState(!request.getConfigTypes().isEmpty(), "Must include at least one configType.");

    final Set<ConfigType> configTypes = request.getConfigTypes()
        .stream()
        .map(type -> Enums.convertTo(type, JobConfig.ConfigType.class))
        .collect(Collectors.toSet());

    final String configId = request.getConfigId();

    final int pageSize = (request.getPagination() != null && request.getPagination().getPageSize() != null) ? request.getPagination().getPageSize()
        : DEFAULT_PAGE_SIZE;
    final List<Job> jobs;

    if (request.getIncludingJobId() != null) {
      jobs = jobPersistence.listJobsIncludingId(
          configTypes,
          configId,
          request.getIncludingJobId(),
          pageSize);
    } else {
      jobs = jobPersistence.listJobs(configTypes, configId, pageSize,
          (request.getPagination() != null && request.getPagination().getRowOffset() != null) ? request.getPagination().getRowOffset() : 0,
          request.getStatuses() == null ? null : mapToDomainJobStatus(request.getStatuses()),
          request.getCreatedAtStart(),
          request.getCreatedAtEnd(),
          request.getUpdatedAtStart(),
          request.getUpdatedAtEnd(),
          request.getOrderByField() == null ? null : request.getOrderByField().value(),
          request.getOrderByMethod() == null ? null : request.getOrderByMethod().value());
    }

    final List<JobWithAttemptsRead> jobReads = jobs.stream().map(JobConverter::getJobWithAttemptsRead).collect(Collectors.toList());

    hydrateWithStats(
        jobReads,
        jobs,
        featureFlagClient.boolVariation(HydrateAggregatedStats.INSTANCE, new Workspace(ANONYMOUS)),
        jobPersistence);

    final Long totalJobCount = jobPersistence.getJobCount(configTypes, configId,
        request.getStatuses() == null ? null : mapToDomainJobStatus(request.getStatuses()),
        request.getCreatedAtStart(),
        request.getCreatedAtEnd(),
        request.getUpdatedAtStart(),
        request.getUpdatedAtEnd());
    return new JobReadList().jobs(jobReads).totalJobCount(totalJobCount);
  }

  @SuppressWarnings("UnstableApiUsage")
  public JobReadList listJobsForWorkspaces(final JobListForWorkspacesRequestBody request) throws IOException {

    Preconditions.checkNotNull(request.getConfigTypes(), "configType cannot be null.");
    Preconditions.checkState(!request.getConfigTypes().isEmpty(), "Must include at least one configType.");

    final Set<ConfigType> configTypes = request.getConfigTypes()
        .stream()
        .map(type -> Enums.convertTo(type, JobConfig.ConfigType.class))
        .collect(Collectors.toSet());

    final int pageSize = (request.getPagination() != null && request.getPagination().getPageSize() != null) ? request.getPagination().getPageSize()
        : DEFAULT_PAGE_SIZE;

    final int offset =
        (request.getPagination() != null && request.getPagination().getRowOffset() != null) ? request.getPagination().getRowOffset() : 0;

    final List<Job> jobs = jobPersistence.listJobs(
        configTypes,
        request.getWorkspaceIds(),
        pageSize,
        offset,
        request.getStatuses() == null ? null : mapToDomainJobStatus(request.getStatuses()),
        request.getCreatedAtStart(),
        request.getCreatedAtEnd(),
        request.getUpdatedAtStart(),
        request.getUpdatedAtEnd(),
        request.getOrderByField() == null ? null : request.getOrderByField().value(),
        request.getOrderByMethod() == null ? null : request.getOrderByMethod().value());

    final List<JobWithAttemptsRead> jobReads = jobs.stream().map(JobConverter::getJobWithAttemptsRead).collect(Collectors.toList());

    hydrateWithStats(
        jobReads,
        jobs,
        featureFlagClient.boolVariation(HydrateAggregatedStats.INSTANCE, new Workspace(ANONYMOUS)),
        jobPersistence);

    return new JobReadList().jobs(jobReads).totalJobCount((long) jobs.size());
  }

  public static Map<StreamNameAndNamespace, SyncMode> getStreamsToSyncMode(Job job) {
    List<ConfiguredAirbyteStream> configuredAirbyteStreams = extractStreams(job);
    return configuredAirbyteStreams.stream()
        .collect(Collectors.toMap(
            configuredStream -> new StreamNameAndNamespace(configuredStream.getStream().getName(), configuredStream.getStream().getNamespace()),
            ConfiguredAirbyteStream::getSyncMode));
  }

  private static List<ConfiguredAirbyteStream> extractStreams(Job job) {
    final var configuredCatalog = new JobConfigProxy(job.getConfig()).getConfiguredCatalog();
    return configuredCatalog != null ? configuredCatalog.getStreams() : List.of();
  }

  public JobInfoRead getJobInfo(final JobIdRequestBody jobIdRequestBody) throws IOException {
    final Job job = jobPersistence.getJob(jobIdRequestBody.getId());
    return jobConverter.getJobInfoRead(job);
  }

  public JobInfoRead getJobInfoWithoutLogs(final JobIdRequestBody jobIdRequestBody) throws IOException {
    final Job job = jobPersistence.getJob(jobIdRequestBody.getId());

    final JobWithAttemptsRead jobWithAttemptsRead = JobConverter.getJobWithAttemptsRead(job);
    hydrateWithStats(List.of(jobWithAttemptsRead), List.of(job), true, jobPersistence);

    return new JobInfoRead()
        .job(jobWithAttemptsRead.getJob())
        .attempts(job.getAttempts().stream().map(JobConverter::getAttemptInfoWithoutLogsRead).collect(Collectors.toList()));
  }

  public JobInfoLightRead getJobInfoLight(final JobIdRequestBody jobIdRequestBody) throws IOException {
    final Job job = jobPersistence.getJob(jobIdRequestBody.getId());
    return jobConverter.getJobInfoLightRead(job);
  }

  public JobOptionalRead getLastReplicationJob(final ConnectionIdRequestBody connectionIdRequestBody) throws IOException {
    final Optional<Job> job = jobPersistence.getLastReplicationJob(connectionIdRequestBody.getConnectionId());
    if (job.isEmpty()) {
      return new JobOptionalRead();
    } else {
      return jobConverter.getJobOptionalRead(job.get());
    }

  }

  public JobDebugInfoRead getJobDebugInfo(final JobIdRequestBody jobIdRequestBody)
      throws ConfigNotFoundException, IOException, JsonValidationException {
    final Job job = jobPersistence.getJob(jobIdRequestBody.getId());
    final JobInfoRead jobinfoRead = jobConverter.getJobInfoRead(job);

    for (final AttemptInfoRead a : jobinfoRead.getAttempts()) {
      final int attemptNumber = a.getAttempt().getId().intValue();
      final var attemptStats = jobPersistence.getAttemptStats(job.getId(), attemptNumber);
      hydrateWithStats(a.getAttempt(), attemptStats);
    }

    final JobDebugInfoRead jobDebugInfoRead = buildJobDebugInfoRead(jobinfoRead);
    if (temporalClient != null) {
      final UUID connectionId = UUID.fromString(job.getScope());
      temporalClient.getWorkflowState(connectionId)
          .map(workflowStateConverter::getWorkflowStateRead)
          .ifPresent(jobDebugInfoRead::setWorkflowState);
    }

    return jobDebugInfoRead;
  }

  public Optional<JobRead> getLatestRunningSyncJob(final UUID connectionId) throws IOException {
    final List<Job> nonTerminalSyncJobsForConnection = jobPersistence.listJobsForConnectionWithStatuses(
        connectionId,
        SYNC_REPLICATION_TYPES,
        JobStatus.NON_TERMINAL_STATUSES);

    // there *should* only be a single running sync job for a connection, but
    // jobPersistence.listJobsForConnectionWithStatuses orders by created_at desc so
    // .findFirst will always return what we want.
    return nonTerminalSyncJobsForConnection.stream().map(JobConverter::getJobRead).findFirst();
  }

  public List<ConnectionSyncProgressReadItem> getConnectionSyncProgress(final ConnectionIdRequestBody connectionIdRequestBody) throws IOException {
    final List<Job> jobs = jobPersistence.getRunningSyncJobForConnections(List.of(connectionIdRequestBody.getConnectionId()));

    if (jobs.isEmpty()) {
      return Collections.emptyList();
    }

    final List<JobWithAttemptsRead> jobReads = jobs.stream()
        .map(JobConverter::getJobWithAttemptsRead)
        .collect(Collectors.toList());

    hydrateWithStats(jobReads, jobs, featureFlagClient.boolVariation(HydrateAggregatedStats.INSTANCE, new Workspace(ANONYMOUS)), jobPersistence);

    return jobReads.stream()
        .flatMap(job -> job.getJob().getStreamAggregatedStats().stream())
        .map(streamStats -> new ConnectionSyncProgressReadItem()
            .jobId(jobReads.getFirst().getJob().getId())
            .streamName(streamStats.getStreamName())
            .streamNamespace(streamStats.getStreamNamespace())
            .recordsExtracted(streamStats.getRecordsEmitted())
            .recordsLoaded(streamStats.getRecordsCommitted())
            .bytesExtracted(streamStats.getBytesEmitted())
            .bytesLoaded(streamStats.getBytesCommitted())
            .syncStartedAt(jobReads.getFirst().getJob().getStartedAt()))
        .collect(Collectors.toList());
  }

  public Optional<JobRead> getLatestSyncJob(final UUID connectionId) throws IOException {
    return jobPersistence.getLastSyncJob(connectionId).map(JobConverter::getJobRead);
  }

  public List<JobStatusSummary> getLatestSyncJobsForConnections(final List<UUID> connectionIds) throws IOException {
    return jobPersistence.getLastSyncJobForConnections(connectionIds);
  }

  public AttemptNormalizationStatusReadList getAttemptNormalizationStatuses(final JobIdRequestBody jobIdRequestBody) throws IOException {
    return new AttemptNormalizationStatusReadList()
        .attemptNormalizationStatuses(jobPersistence.getAttemptNormalizationStatusesForJob(jobIdRequestBody.getId()).stream()
            .map(JobConverter::convertAttemptNormalizationStatus).collect(Collectors.toList()));
  }

  public List<JobRead> getRunningSyncJobForConnections(final List<UUID> connectionIds) throws IOException {
    return jobPersistence.getRunningSyncJobForConnections(connectionIds).stream()
        .map(JobConverter::getJobRead)
        .collect(Collectors.toList());
  }

  private SourceRead getSourceRead(final ConnectionRead connectionRead) throws JsonValidationException, IOException, ConfigNotFoundException {
    final SourceIdRequestBody sourceIdRequestBody = new SourceIdRequestBody().sourceId(connectionRead.getSourceId());
    return sourceHandler.getSource(sourceIdRequestBody);
  }

  private DestinationRead getDestinationRead(final ConnectionRead connectionRead)
      throws JsonValidationException, IOException, ConfigNotFoundException {
    final DestinationIdRequestBody destinationIdRequestBody = new DestinationIdRequestBody().destinationId(connectionRead.getDestinationId());
    return destinationHandler.getDestination(destinationIdRequestBody);
  }

  private SourceDefinitionRead getSourceDefinitionRead(final SourceRead sourceRead)
      throws JsonValidationException, IOException, ConfigNotFoundException {
    final SourceDefinitionIdRequestBody sourceDefinitionIdRequestBody =
        new SourceDefinitionIdRequestBody().sourceDefinitionId(sourceRead.getSourceDefinitionId());
    return sourceDefinitionsHandler.getSourceDefinition(sourceDefinitionIdRequestBody);
  }

  private DestinationDefinitionRead getDestinationDefinitionRead(final DestinationRead destinationRead)
      throws JsonValidationException, IOException, ConfigNotFoundException {
    final DestinationDefinitionIdRequestBody destinationDefinitionIdRequestBody =
        new DestinationDefinitionIdRequestBody().destinationDefinitionId(destinationRead.getDestinationDefinitionId());
    return destinationDefinitionsHandler.getDestinationDefinition(destinationDefinitionIdRequestBody);
  }

  private JobDebugInfoRead buildJobDebugInfoRead(final JobInfoRead jobInfoRead)
      throws ConfigNotFoundException, IOException, JsonValidationException {
    final String configId = jobInfoRead.getJob().getConfigId();
    final StandardSync standardSync;
    try {
      standardSync = connectionService.getStandardSync(UUID.fromString(configId));
    } catch (io.airbyte.data.exceptions.ConfigNotFoundException e) {
      throw new ConfigNotFoundException(e.getType(), e.getMessage());
    }

    final ConnectionRead connection = ApiPojoConverters.internalToConnectionRead(standardSync);
    final SourceRead source = getSourceRead(connection);
    final DestinationRead destination = getDestinationRead(connection);
    final SourceDefinitionRead sourceDefinitionRead = getSourceDefinitionRead(source);
    final DestinationDefinitionRead destinationDefinitionRead = getDestinationDefinitionRead(destination);
    final JobDebugRead jobDebugRead = JobConverter.getDebugJobInfoRead(jobInfoRead, sourceDefinitionRead, destinationDefinitionRead, airbyteVersion);

    return new JobDebugInfoRead()
        .attempts(jobInfoRead.getAttempts())
        .job(jobDebugRead);
  }

  public List<JobStatus> mapToDomainJobStatus(List<io.airbyte.api.model.generated.JobStatus> apiJobStatuses) {
    return apiJobStatuses.stream()
        .map(apiJobStatus -> JobStatus.valueOf(apiJobStatus.toString().toUpperCase()))
        .collect(Collectors.toList());
  }

  public record StreamNameAndNamespace(String name, String namespace) {}

}
