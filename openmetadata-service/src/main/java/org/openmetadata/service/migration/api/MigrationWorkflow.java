package org.openmetadata.service.migration.api;

import static org.openmetadata.service.util.OpenMetadataOperations.printToAsciiTable;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.json.JSONObject;
import org.openmetadata.service.jdbi3.MigrationDAO;
import org.openmetadata.service.jdbi3.locator.ConnectionType;
import org.openmetadata.service.migration.QueryStatus;
import org.openmetadata.service.migration.context.MigrationContext;
import org.openmetadata.service.migration.context.MigrationWorkflowContext;
import org.openmetadata.service.migration.utils.MigrationFile;
import org.openmetadata.service.util.AsciiTable;

@Slf4j
public class MigrationWorkflow {
  public static final String SUCCESS_MSG = "Success";
  public static final String FAILED_MSG = "Failed due to : ";
  private List<MigrationProcess> migrations;
  private final String nativeSQLScriptRootPath;
  private final ConnectionType connectionType;
  private final String extensionSQLScriptRootPath;
  private final MigrationDAO migrationDAO;
  private final Jdbi jdbi;
  private final boolean forceMigrations;
  private Optional<String> currentMaxMigrationVersion;

  public MigrationWorkflow(
      Jdbi jdbi,
      String nativeSQLScriptRootPath,
      ConnectionType connectionType,
      String extensionSQLScriptRootPath,
      boolean forceMigrations) {
    this.jdbi = jdbi;
    this.migrationDAO = jdbi.onDemand(MigrationDAO.class);
    this.forceMigrations = forceMigrations;
    this.nativeSQLScriptRootPath = nativeSQLScriptRootPath;
    this.connectionType = connectionType;
    this.extensionSQLScriptRootPath = extensionSQLScriptRootPath;
  }

  public void loadMigrations() {
    // Sort Migration on the basis of version
    List<MigrationFile> availableMigrations =
        getMigrationFiles(nativeSQLScriptRootPath, connectionType, extensionSQLScriptRootPath);
    // Filter Migrations to Be Run
    this.migrations = filterAndGetMigrationsToRun(availableMigrations);
  }

  public void validateMigrationsForServer() {
    if (!migrations.isEmpty()) {
      throw new IllegalStateException(
          "There are pending migrations to be run on the database."
              + " Please backup your data and run `./bootstrap/bootstrap_storage.sh migrate-all`."
              + " You can find more information on upgrading OpenMetadata at"
              + " https://docs.open-metadata.org/deployment/upgrade ");
    }
  }

  public List<MigrationFile> getMigrationFiles(
      String nativeSQLScriptRootPath,
      ConnectionType connectionType,
      String extensionSQLScriptRootPath) {
    List<MigrationFile> availableOMNativeMigrations =
        getMigrationFilesFromPath(nativeSQLScriptRootPath, connectionType);

    // If we only have OM migrations, return them
    if (extensionSQLScriptRootPath == null || extensionSQLScriptRootPath.isEmpty()) {
      return availableOMNativeMigrations;
    }

    // Otherwise, fetch the extension migrations and sort the executions
    List<MigrationFile> availableExtensionMigrations =
        getMigrationFilesFromPath(extensionSQLScriptRootPath, connectionType);

    /*
     If we create migrations version as:
       - OpenMetadata: 1.1.0, 1.1.1, 1.2.0
       - Extension: 1.1.0-extension, 1.2.0-extension
     The end result will be 1.1.0, 1.1.0-extension, 1.1.1, 1.2.0, 1.2.0-extension
    */
    return Stream.concat(
            availableOMNativeMigrations.stream(), availableExtensionMigrations.stream())
        .sorted()
        .toList();
  }

  public List<MigrationFile> getMigrationFilesFromPath(String path, ConnectionType connectionType) {
    return Arrays.stream(Objects.requireNonNull(new File(path).listFiles(File::isDirectory)))
        .map(dir -> new MigrationFile(dir, migrationDAO, connectionType))
        .sorted()
        .toList();
  }

  private List<MigrationProcess> filterAndGetMigrationsToRun(
      List<MigrationFile> availableMigrations) {
    LOG.debug("Filtering Server Migrations");
    currentMaxMigrationVersion = migrationDAO.getMaxServerMigrationVersion();
    List<MigrationFile> applyMigrations;
    if (currentMaxMigrationVersion.isPresent() && !forceMigrations) {
      applyMigrations =
          availableMigrations.stream()
              .filter(migration -> migration.biggerThan(currentMaxMigrationVersion.get()))
              .toList();
    } else {
      applyMigrations = availableMigrations;
    }
    List<MigrationProcess> processes = new ArrayList<>();
    try {
      for (MigrationFile file : applyMigrations) {
        file.parseSQLFiles();
        String clazzName = file.getMigrationProcessClassName();
        MigrationProcess process =
            (MigrationProcess)
                Class.forName(clazzName).getConstructor(MigrationFile.class).newInstance(file);
        processes.add(process);
      }
    } catch (Exception e) {
      LOG.error("Failed to list and add migrations to run due to ", e);
    }
    return processes;
  }

  public void printMigrationInfo() {
    LOG.info("Following Migrations will be performed, with Force Migration : {}", forceMigrations);
    List<String> columns = Arrays.asList("Version", "ConnectionType", "MigrationsFilePath");
    List<List<String>> allRows = new ArrayList<>();
    for (MigrationProcess process : migrations) {
      List<String> row = new ArrayList<>();
      row.add(process.getVersion());
      row.add(process.getDatabaseConnectionType());
      row.add(process.getMigrationsPath());
      allRows.add(row);
    }
    printToAsciiTable(columns.stream().toList(), allRows, "No Server Migration To be Run");
  }

  public void runMigrationWorkflows() {
    List<String> columns =
        Arrays.asList(
            "Version",
            "Initialization",
            "SchemaChanges",
            "DataMigration",
            "PostDDLScripts",
            "Context");
    List<List<String>> allRows = new ArrayList<>();
    try (Handle transactionHandler = jdbi.open()) {
      MigrationWorkflowContext context = new MigrationWorkflowContext(transactionHandler);
      if (currentMaxMigrationVersion.isPresent()) {
        LOG.debug("Current Max version {}", currentMaxMigrationVersion.get());
        context.computeInitialContext(currentMaxMigrationVersion.get());
      } else {
        context.computeInitialContext("1.1.0");
      }
      LOG.info("[MigrationWorkflow] WorkFlow Started");
      try {
        for (MigrationProcess process : migrations) {
          // Initialise Migration Steps
          LOG.info(
              "[MigrationWorkFlow] Migration Run started for Version: {}, with Force Migration : {}",
              process.getVersion(),
              forceMigrations);

          List<String> row = new ArrayList<>();
          row.add(process.getVersion());
          try {
            // Initialize
            runStepAndAddStatus(row, () -> process.initialize(transactionHandler));

            // Schema Changes
            runSchemaChanges(row, process);

            // Data Migration
            runStepAndAddStatus(row, process::runDataMigration);

            // Post DDL Scripts
            runPostDDLChanges(row, process);

            // Build Context
            context.computeMigrationContext(process);
            row.add(
                context.getMigrationContext().get(process.getVersion()).getResults().toString());

            // Handle Migration Closure
            updateMigrationStepInDB(process, context);
          } finally {
            allRows.add(row);
            LOG.info(
                "[MigrationWorkFlow] Migration Run finished for Version: {}", process.getVersion());
          }
        }
        printToAsciiTable(columns, allRows, "Status Unavailable");
      } catch (Exception e) {
        // Any Exception catch the error
        LOG.error("Encountered Exception in MigrationWorkflow", e);
        throw e;
      }
    }
    LOG.info("[MigrationWorkflow] WorkFlow Completed");
  }

  private void runSchemaChanges(List<String> row, MigrationProcess process) {
    try {
      List<String> schemaChangesColumns = Arrays.asList("Query", "Query Status");
      Map<String, QueryStatus> queryStatusMap = process.runSchemaChanges(forceMigrations);
      List<List<String>> allSchemaChangesRows =
          new ArrayList<>(
              queryStatusMap.entrySet().stream()
                  .map(
                      entry ->
                          Arrays.asList(
                              entry.getKey(),
                              String.format(
                                  "Status : %s , Message: %s",
                                  entry.getValue().getStatus(), entry.getValue().getMessage())))
                  .toList());
      LOG.info(
          "[MigrationWorkflow] Version : {} Run Schema Changes Query Status", process.getVersion());
      LOG.debug(
          new AsciiTable(schemaChangesColumns, allSchemaChangesRows, true, "", "No New Queries")
              .render());
      row.add(SUCCESS_MSG);
    } catch (Exception e) {
      row.add(FAILED_MSG + e.getMessage());
      if (!forceMigrations) {
        throw e;
      }
    }
  }

  private void runPostDDLChanges(List<String> row, MigrationProcess process) {
    try {
      List<String> schemaChangesColumns = Arrays.asList("Query", "Query Status");
      Map<String, QueryStatus> queryStatusMap = process.runPostDDLScripts(forceMigrations);
      List<List<String>> allSchemaChangesRows =
          new ArrayList<>(
              queryStatusMap.entrySet().stream()
                  .map(
                      entry ->
                          Arrays.asList(
                              entry.getKey(),
                              String.format(
                                  "Status : %s , Message: %s",
                                  entry.getValue().getStatus(), entry.getValue().getMessage())))
                  .toList());
      LOG.info("[MigrationWorkflow] Version : {} Run Post DDL Query Status", process.getVersion());
      LOG.debug(
          new AsciiTable(schemaChangesColumns, allSchemaChangesRows, true, "", "No New Queries")
              .render());
      row.add(SUCCESS_MSG);
    } catch (Exception e) {
      row.add(FAILED_MSG + e.getMessage());
      if (!forceMigrations) {
        throw e;
      }
    }
  }

  private void runStepAndAddStatus(
      List<String> row, MigrationProcess.MigrationProcessCallback process) {
    try {
      process.call();
      row.add(SUCCESS_MSG);
    } catch (Exception e) {
      row.add(FAILED_MSG + e.getMessage());
      if (!forceMigrations) {
        throw e;
      }
    }
  }

  public void updateMigrationStepInDB(
      MigrationProcess step, MigrationWorkflowContext workflowContext) {
    MigrationContext context = workflowContext.getMigrationContext().get(step.getVersion());
    JSONObject metrics = new JSONObject(context.getResults());
    migrationDAO.upsertServerMigration(
        step.getVersion(),
        step.getMigrationsPath(),
        UUID.randomUUID().toString(),
        metrics.toString());
  }
}
