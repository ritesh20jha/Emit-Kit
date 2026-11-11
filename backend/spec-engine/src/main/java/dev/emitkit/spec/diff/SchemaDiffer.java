package dev.emitkit.spec.diff;

import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Compares the schemas of a shared parameter, request body, or response. Each side may be null when
 * absent. Implementations resolve {@code $ref} through their model's {@code schemas} map.
 */
public interface SchemaDiffer {

  /** Reports no schema changes. The default until a real schema differ is supplied. */
  SchemaDiffer NONE =
      new SchemaDiffer() {
        @Override
        public List<Change> diffRequest(
            ApiModel before,
            @Nullable Schema beforeSchema,
            ApiModel after,
            @Nullable Schema afterSchema,
            String location) {
          return List.of();
        }

        @Override
        public List<Change> diffResponse(
            ApiModel before,
            @Nullable Schema beforeSchema,
            ApiModel after,
            @Nullable Schema afterSchema,
            String location) {
          return List.of();
        }
      };

  List<Change> diffRequest(
      ApiModel before,
      @Nullable Schema beforeSchema,
      ApiModel after,
      @Nullable Schema afterSchema,
      String location);

  List<Change> diffResponse(
      ApiModel before,
      @Nullable Schema beforeSchema,
      ApiModel after,
      @Nullable Schema afterSchema,
      String location);
}
