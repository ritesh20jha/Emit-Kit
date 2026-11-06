package dev.emitkit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One version of a project's generation settings. Append-only: a change inserts a new row with the
 * next version, and the latest row is the current config. Maps to {@code project_configs}.
 */
@Entity
@Table(name = "project_configs")
public class ProjectConfig extends BaseEntity {

  /** Field initializers mirror the column defaults in V2__projects.sql. */
  private static final String DEFAULT_OUTPUT_DIR = ".emitkit/";

  private static final String DEFAULT_AI_PROVIDER = "gemini";

  @Column(name = "project_id", nullable = false, length = 26)
  private String projectId;

  @Column(name = "version", nullable = false)
  private int version;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "outputs", nullable = false)
  private List<OutputKind> outputs = new ArrayList<>();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "sdk_languages", nullable = false)
  private List<String> sdkLanguages = new ArrayList<>();

  @Column(name = "output_dir", nullable = false, length = 255)
  private String outputDir = DEFAULT_OUTPUT_DIR;

  @Column(name = "npm_scope", length = 255)
  private String npmScope;

  @Column(name = "pypi_name", length = 255)
  private String pypiName;

  @Column(name = "maven_group_id", length = 255)
  private String mavenGroupId;

  @Column(name = "maven_artifact_id", length = 255)
  private String mavenArtifactId;

  @Enumerated(EnumType.STRING)
  @Column(name = "version_strategy", nullable = false, length = 32)
  private VersionStrategy versionStrategy;

  @Enumerated(EnumType.STRING)
  @Column(name = "breaking_gate", nullable = false, length = 32)
  private BreakingGate breakingGate = BreakingGate.ALLOW;

  @Enumerated(EnumType.STRING)
  @Column(name = "lint_fail_on", nullable = false, length = 16)
  private LintFailOn lintFailOn = LintFailOn.NEVER;

  @Column(name = "ai_provider", nullable = false, length = 32)
  private String aiProvider = DEFAULT_AI_PROVIDER;

  @Column(name = "ai_model", length = 255)
  private String aiModel;

  @Column(name = "ai_api_key_enc")
  private String aiApiKeyEnc;

  @Column(name = "created_by", nullable = false, length = 26)
  private String createdBy;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected ProjectConfig() {}

  /**
   * Creates a config version. The optional settings start at their column defaults and can be set
   * with the setters before the row is saved.
   *
   * @param id the ULID to use, or {@code null} to generate one
   * @param version the version number, one more than the project's latest
   * @param createdBy the id of the user who saved this version
   */
  public ProjectConfig(
      String id, String projectId, int version, VersionStrategy versionStrategy, String createdBy) {
    super(id);
    this.projectId = projectId;
    this.version = version;
    this.versionStrategy = versionStrategy;
    this.createdBy = createdBy;
  }

  public String getProjectId() {
    return projectId;
  }

  public int getVersion() {
    return version;
  }

  public List<OutputKind> getOutputs() {
    return outputs;
  }

  public void setOutputs(List<OutputKind> outputs) {
    this.outputs = outputs;
  }

  public List<String> getSdkLanguages() {
    return sdkLanguages;
  }

  public void setSdkLanguages(List<String> sdkLanguages) {
    this.sdkLanguages = sdkLanguages;
  }

  public String getOutputDir() {
    return outputDir;
  }

  public void setOutputDir(String outputDir) {
    this.outputDir = outputDir;
  }

  public String getNpmScope() {
    return npmScope;
  }

  public void setNpmScope(String npmScope) {
    this.npmScope = npmScope;
  }

  public String getPypiName() {
    return pypiName;
  }

  public void setPypiName(String pypiName) {
    this.pypiName = pypiName;
  }

  public String getMavenGroupId() {
    return mavenGroupId;
  }

  public void setMavenGroupId(String mavenGroupId) {
    this.mavenGroupId = mavenGroupId;
  }

  public String getMavenArtifactId() {
    return mavenArtifactId;
  }

  public void setMavenArtifactId(String mavenArtifactId) {
    this.mavenArtifactId = mavenArtifactId;
  }

  public VersionStrategy getVersionStrategy() {
    return versionStrategy;
  }

  public BreakingGate getBreakingGate() {
    return breakingGate;
  }

  public void setBreakingGate(BreakingGate breakingGate) {
    this.breakingGate = breakingGate;
  }

  public LintFailOn getLintFailOn() {
    return lintFailOn;
  }

  public void setLintFailOn(LintFailOn lintFailOn) {
    this.lintFailOn = lintFailOn;
  }

  public String getAiProvider() {
    return aiProvider;
  }

  public void setAiProvider(String aiProvider) {
    this.aiProvider = aiProvider;
  }

  public String getAiModel() {
    return aiModel;
  }

  public void setAiModel(String aiModel) {
    this.aiModel = aiModel;
  }

  public String getAiApiKeyEnc() {
    return aiApiKeyEnc;
  }

  public void setAiApiKeyEnc(String aiApiKeyEnc) {
    this.aiApiKeyEnc = aiApiKeyEnc;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
