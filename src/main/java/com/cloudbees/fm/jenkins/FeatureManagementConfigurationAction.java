package com.cloudbees.fm.jenkins;

import com.cloudbees.diff.Diff;
import com.cloudbees.fm.jenkins.ui.AuditLogMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.model.Run;
import io.rollout.configuration.comparison.ComparisonResult;
import io.rollout.configuration.comparison.ConfigurationComparator;
import io.rollout.publicapi.model.Application;
import io.rollout.publicapi.model.AuditLog;
import io.rollout.publicapi.model.ConfigEntity;
import io.rollout.publicapi.model.DataPersister;
import io.rollout.publicapi.model.Environment;
import io.rollout.publicapi.model.Flag;
import io.rollout.publicapi.model.TargetGroup;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import jenkins.model.RunAction2;
import org.apache.commons.io.IOUtils;

public class FeatureManagementConfigurationAction implements RunAction2 {

    private final Application application;
    private final Environment environment;
    private transient Run<?, ?> run;

    FeatureManagementConfigurationAction(Application application, Environment environment) {
        this.application = application;
        this.environment = environment;
    }

    @Override
    public String getIconFileName() {
        return "/plugin/cloudbees-feature-management/images/cloudbees.svg";
    }

    @Override
    public String getDisplayName() {
        return "Flag configurations";
    }

    @Override
    public String getUrlName() {
        return "flags";
    }

    @Override
    public void onAttached(Run<?, ?> r) {
        run = r;
    }

    @Override
    public void onLoad(Run<?, ?> r) {
        run = r;
    }

    public Run<?, ?> getOwner() {
        return run;
    }

    public Application getApplication() {
        return application;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public String toJson(Object o) throws JsonProcessingException {
        return o == null ? "" : new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(o);
    }

    private List<Flag> getFlags(Run<?, ?> run) throws IOException {
        if (run != null) {
            return DataPersister.readValue(run.getRootDir(), environment.getKey(), DataPersister.EntityType.FLAG, new TypeReference<List<Flag>>() {}, Collections.emptyList())
                    .stream()
                    .filter(Flag::isEnabled)
                    .collect(Collectors.toList());
        } else {
            return Collections.emptyList();
        }
    }

    public List<Flag> getFlags() throws IOException {
        return getFlags(run);
    }

    private List<Flag> getPreviousSuccessfulFlags() throws IOException {
        return getFlags(run.getPreviousSuccessfulBuild());
    }

    public List<TargetGroup> getTargetGroups() throws IOException {
        return getTargetGroups(run);
    }

    private List<TargetGroup> getPreviousSuccessfulTargetGroups() throws IOException {
        return getTargetGroups(run.getPreviousSuccessfulBuild());
    }

    private List<TargetGroup> getTargetGroups(Run<?, ?> run) throws IOException {
        if (run != null) {
            return DataPersister.readValue(run.getRootDir(), environment.getKey(), DataPersister.EntityType.TARGET_GROUP, new TypeReference<List<TargetGroup>>() {}, Collections.emptyList());
        } else {
            return Collections.emptyList();
        }
    }

    public List<AuditLog> getAuditLogs() throws IOException {
        return DataPersister.readValue(run.getRootDir(), environment.getKey(), DataPersister.EntityType.AUDIT_LOG, new TypeReference<List<AuditLog>>() {}, Collections.emptyList());
    }

    public static AuditLogMessage prettify(AuditLog auditLog) {
        return new AuditLogMessage(auditLog.getMessage());
    }

    public String getRawFlags() throws IOException {
        return IOUtils.toString(new FileInputStream(DataPersister.filename(run.getRootDir(), environment.getKey(), DataPersister.EntityType.FLAG)), StandardCharsets.UTF_8);
    }

    public String getRawTargetGroups() throws IOException {
        return IOUtils.toString(new FileInputStream(DataPersister.filename(run.getRootDir(), environment.getKey(), DataPersister.EntityType.TARGET_GROUP)), StandardCharsets.UTF_8);
    }

    public Run<?, ?> getPreviousSuccessfulBuild() {
        return run.getPreviousSuccessfulBuild();
    }

    public boolean getHasChanged() throws IOException {
        return !getFlagChanges().areEqual() || !getTargetGroupChanges().areEqual();
    }

    public ComparisonResult<Flag> getFlagChanges() throws IOException {
        return new ConfigurationComparator().compare(getFlags(), getPreviousSuccessfulFlags());
    }

    public ComparisonResult<TargetGroup> getTargetGroupChanges() throws IOException {
        return new ConfigurationComparator().compare(getTargetGroups(), getPreviousSuccessfulTargetGroups());
    }

    public String getUrl() {
        return Jenkins.get().getRootUrl() + run.getUrl() + getUrlName() + "/";
    }

    public String generateDiff(ComparisonResult<? extends ConfigEntity> comparisonResult) {
        // Generate a Unified Diff with all the entity changes
        StringBuilder builder = new StringBuilder();

        // First get all the new elements
        comparisonResult.getInSecondOnly().forEach(entity -> {
            try {
                String json = toJson(entity);
                String diff = Diff.diff(new StringReader(""), new StringReader(json), true)
                        .toUnifiedDiff(entity.getName(), entity.getName(), new StringReader(""), new StringReader(json), 100); // Don't use Integer.MAX_VALUE here, but give it a big enough value so that it shows the whole config
                builder.append("diff\n")
                        .append("new file mode 100666\n")
                        .append(diff);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        // Now get all the deleted elements
        comparisonResult.getInFirstOnly().forEach(entity -> {
            try {
                String json = toJson(entity);
                String diff = Diff.diff(new StringReader(json), new StringReader(""), true)
                        .toUnifiedDiff(entity.getName(), entity.getName(), new StringReader(json), new StringReader(""), 100); // Don't use Integer.MAX_VALUE here, but give it a big enough value so that it shows the whole config
                builder.append("diff\n")
                        .append("deleted file mode 100666\n")
                        .append(diff);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        // Now get all the changed elements
        comparisonResult.getInBothButDifferent().stream()
            .forEach(entity -> {
                try {
                    String left = toJson(entity.getLeft()).trim();
                    String right = toJson(entity.getRight()).trim();
                    String name = entity.getLeft().getName();

                    String diff = Diff.diff(new StringReader(left), new StringReader(right), true)
                            .toUnifiedDiff(name, name, new StringReader(left), new StringReader(right), 100); // Don't use Integer.MAX_VALUE here, but give it a big enough value so that it shows the whole config
                    builder.append("diff\n").append(diff);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

            });

        return builder.toString();
    }

}
