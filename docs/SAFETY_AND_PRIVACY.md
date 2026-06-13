# Safety, Privacy & Anti-Abuse

Implements SPEC §9. The guiding principle: **motivating without becoming
harmful**. The system nudges; it never blocks, shames, or pressures.

## Safety signals (`SafetyGuard`)

The guard reads recent completion history and raises supportive, non-blocking
signals. Thresholds are conservative (false alarms are annoying) and live in
`SafetyGuard.Config`.

| Signal | Trigger | Framing |
|--------|---------|---------|
| `REST_SUGGESTION` | 10+ consecutive active days | "A planned rest day keeps this sustainable — recovery is part of progress." |
| `OVERDRIVE` (warning) | a single day with 12+ quests | "Doing more isn't always better — consider trimming tomorrow." |
| `META_HEAVY` (info) | ≥50% of recent progress is meta-maintenance | "Real-world quests are where the meaningful wins are." |
| `RECOVERY_MODE` | ≥60% miss rate over the window | "Rough stretch — let's shrink the list to a couple of small wins." |

These directly counter the spec's anti-patterns: compulsive use, overwork,
over-optimization, and shame spirals.

## Anti-abuse (built into the economy)

- **Same-day repeat decay** stops farming one easy quest (see Reward Economy).
- **Meta-maintenance daily cap** stops gaming progress with system chores.
- **Difficulty-weighted** completion stops trivial-task spamming from dominating.
- **Honesty rewards** remove the incentive to lie about bad habits.
- The economy is centralised in `RewardConfig` for periodic rebalancing.

The goal is *not* perfect enforcement — it's helping users keep a fair
self-motivation system.

## Fairness on hard days

- Penalties are gentle and capped; XP never goes negative.
- Streaks have grace days so one missed day doesn't erase weeks.
- Low-energy check-ins shrink the plan and cap difficulty.
- Rescheduling is free.

## Privacy posture

QuestLoop handles sensitive data (schedule, habits, goals, health routines,
behavioural patterns, a self-set budget).

- **Local-first.** All data is stored on-device (Room + DataStore). There is no
  backend in the MVP and nothing is uploaded.
- **Backups off by default.** `backup_rules.xml` / `data_extraction_rules.xml`
  exclude QuestLoop data from cloud backup and device transfer until a user
  opts in.
- **Sensitive notifications are opt-in.** `UserPreferences.sensitiveNotificationsOptIn`
  defaults to false so personal patterns aren't surfaced in notifications.
- **AI transparency.** When AI features are enabled, prompts are versioned
  (`PromptLibrary`) and every generated quest carries a rationale; outputs pass
  through `AiQuestValidator` before display.
- **Data minimisation & deletion.** Quests can be archived; the schema is small
  and contains only what the features need.

## AI guardrails (`AiQuestValidator`)

Model output is treated as untrusted product input:

- Rejects shame/guilt language, financial advice, and medical advice (in titles
  *and* rationales).
- Clamps unrealistic time estimates rather than discarding useful quests.
- De-duplicates against existing quests; drops blank entries.
- Tags bad-habit-reduction quests correctly so relapse is handled with honesty.
- `FallbackSuggester` provides safe, deterministic quests when the model is
  unavailable or its output is unusable — the user is never left stuck or shown
  unsafe text.
