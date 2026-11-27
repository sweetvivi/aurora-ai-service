package com.hub.backend.infra.newapi;

import java.math.BigDecimal;
import java.util.List;

/**
 * Model and price catalogue read from {@code GET /api/pricing}.
 *
 * <p>Keeps the grouped shape the model services consume. The group name is the New API group the
 * price applies to; {@code platform} is the first supported endpoint type, used only as a hint when
 * classifying a model as chat / image / video.</p>
 *
 * <p>{@code availableGroups} is the plaza's own {@code usable_group} map — every group a user may
 * select, with its description and multiplier. It is the only endpoint that publishes the full
 * group list without an administrative credential. {@link Model#enableGroups()} is the model's
 * complete {@code enable_groups} list: New API trims whole model rows by the caller's groups, but
 * never trims that array, so one read still reveals every group a visible model is served in.</p>
 */
public record NewApiModelPlaza(String description,
                               List<Group> groups,
                               List<GroupInfo> availableGroups) {

    public NewApiModelPlaza {
        groups = groups == null ? List.of() : List.copyOf(groups);
        availableGroups = availableGroups == null ? List.of() : List.copyOf(availableGroups);
    }

    /** Catalogue without group metadata, for callers that only fan models out by group. */
    public NewApiModelPlaza(String description, List<Group> groups) {
        this(description, groups, List.of());
    }

    public record Group(Long id, String name, String platform, List<Model> models) {

        public Group {
            models = models == null ? List.of() : List.copyOf(models);
        }
    }

    /** One selectable relay group: the value a token's {@code group} carries, its label, its ratio. */
    public record GroupInfo(String name, String label, BigDecimal ratio) {
    }

    public record Model(String name, String platform, Pricing pricing, List<String> enableGroups) {

        public Model {
            enableGroups = enableGroups == null ? List.of() : List.copyOf(enableGroups);
        }

        public Model(String name, String platform, Pricing pricing) {
            this(name, platform, pricing, List.of());
        }
    }

    public record Pricing(String billingMode,
                          BigDecimal inputPrice,
                          BigDecimal outputPrice,
                          BigDecimal cacheWritePrice,
                          BigDecimal cacheWrite1hPrice,
                          BigDecimal cacheReadPrice,
                          BigDecimal imageInputPrice,
                          BigDecimal imageOutputPrice,
                          BigDecimal perRequestPrice,
                          List<Interval> intervals) {

        public Pricing {
            intervals = intervals == null ? List.of() : List.copyOf(intervals);
        }
    }

    public record Interval(Long minTokens,
                           Long maxTokens,
                           BigDecimal inputPrice,
                           BigDecimal outputPrice,
                           BigDecimal cacheWritePrice,
                           BigDecimal cacheWrite1hPrice,
                           BigDecimal cacheReadPrice,
                           BigDecimal perRequestPrice) {
    }
}
