# Local Collaboration Rules

## PR Comments
- Never post comments/reviews to GitHub pull requests on behalf of `pgorbachev` without explicit user approval in the current conversation.
- Prepare findings first, then wait for confirmation before publishing.
- Exception: if the user directly asks to post/comment now, proceed.

## Skills
A skill is a set of local instructions to follow that is stored in a `SKILL.md` file. Below is the list of skills that can be used. Each entry includes a name, description, and file path so you can open the source for full instructions when using a specific skill.

### Available skills
- anki-greek-picture-words: Add Greek words into Anki note type '2. Picture Words' under deck 'Greek A-B' with duplicate checks, strict grammar form rules, Forvo Greek MP3 audio, Wikimedia Commons image lookup, and translation text in back-side info. Use when the user asks to import Greek vocabulary into local Anki and sync later. (file: /Users/pgorbachev/.codex/skills/anki-greek-picture-words/SKILL.md)
- enable-jvm-quality-gates: Configure and enforce static analysis, security scanning, and test coverage gates for Java, Kotlin, and Groovy components, primarily Gradle-based JVM services. Use when rolling out shared quality workflows from octopus-base, adding repository-specific gating, baselines, coverage split, CI artifact wiring, and documenting blocking vs report-only checks. (file: /Users/pgorbachev/.codex/skills/enable-jvm-quality-gates/SKILL.md)
- export-master-patches: Export IntelliJ IDEA-style .patch files from commits reachable on master after a chosen starting point or from the latest N commits. Use when asked to выгрузить патчи из master, first update local master from central repository (origin/master), save one patch per commit into a dated componentName2inc_date folder, start file names with the task key, skip commits whose subject contains (sync) in any case, generate a readable git-log-to-patch mapping file, optionally fetch Bitbucket pull request metadata, and require user-provided artifacts in the export folder: IDEA commit-tree screenshot plus one document per Jira task. (file: /Users/pgorbachev/.codex/skills/export-master-patches/SKILL.md)
- forvo-greek-audio: Download Greek pronunciation MP3 files from Forvo word pages into a local folder using a deterministic script. Use when a user sends a Forvo word URL and asks to download the Greek audio (typically to Downloads), including repeated requests for new words. (file: /Users/pgorbachev/.codex/skills/forvo-greek-audio/SKILL.md)
- greek-vocab-image-mnemonics: Build deterministic prompts for Greek B1 vocabulary image generation focused on memorization clarity (cause-effect logic, low ambiguity, and fast recognition). Use when generating images for Greek flashcards, especially in Anki pipelines. (file: /Users/pgorbachev/.codex/skills/greek-vocab-image-mnemonics/SKILL.md)
- openai-docs: Use when the user asks how to build with OpenAI products or APIs and needs up-to-date official documentation with citations, help choosing the latest model for a use case, or explicit GPT-5.4 upgrade and prompt-upgrade guidance; prioritize OpenAI docs MCP tools, use bundled references only as helper context, and restrict any fallback browsing to official OpenAI domains. (file: /Users/pgorbachev/.codex/skills/.system/openai-docs/SKILL.md)
- skill-creator: Guide for creating effective skills. This skill should be used when users want to create a new skill (or update an existing skill) that extends Codex's capabilities with specialized knowledge, workflows, or tool integrations. (file: /Users/pgorbachev/.codex/skills/.system/skill-creator/SKILL.md)
- skill-installer: Install Codex skills into $CODEX_HOME/skills from a curated list or a GitHub repo path. Use when a user asks to list installable skills, install a curated skill, or install a skill from another repo (including private repos). (file: /Users/pgorbachev/.codex/skills/.system/skill-installer/SKILL.md)

### How to use skills
- Discovery: The list above is the skills available in this session (name + description + file path). Skill bodies live on disk at the listed paths.
- Trigger rules: If the user names a skill (with `$SkillName` or plain text) OR the task clearly matches a skill's description shown above, you must use that skill for that turn. Multiple mentions mean use them all. Do not carry skills across turns unless re-mentioned.
- Missing/blocked: If a named skill isn't in the list or the path can't be read, say so briefly and continue with the best fallback.
- How to use a skill (progressive disclosure):
  1) After deciding to use a skill, open its `SKILL.md`. Read only enough to follow the workflow.
  2) When `SKILL.md` references relative paths (e.g., `scripts/foo.py`), resolve them relative to the skill directory listed above first, and only consider other paths if needed.
  3) If `SKILL.md` points to extra folders such as `references/`, load only the specific files needed for the request; don't bulk-load everything.
  4) If `scripts/` exist, prefer running or patching them instead of retyping large code blocks.
  5) If `assets/` or templates exist, reuse them instead of recreating from scratch.
- Coordination and sequencing:
  - If multiple skills apply, choose the minimal set that covers the request and state the order you'll use them.
  - Announce which skill(s) you're using and why (one short line). If you skip an obvious skill, say why.
- Context hygiene:
  - Keep context small: summarize long sections instead of pasting them; only load extra files when needed.
  - Avoid deep reference-chasing: prefer opening only files directly linked from `SKILL.md` unless you're blocked.
  - When variants exist (frameworks, providers, domains), pick only the relevant reference file(s) and note that choice.
- Safety and fallback: If a skill can't be applied cleanly (missing files, unclear instructions), state the issue, pick the next-best approach, and continue.
