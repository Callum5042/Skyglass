---
description: Implement a task with the coder agent, review it with the reviewer agent, and loop fixes until approved (max 5 rounds)
argument-hint: <task description>
---

Run a coder → reviewer loop for this task:

<task>
$ARGUMENTS
</task>

If the task above is empty, ask me what to build and stop.

You are the orchestrator. Do not write or edit project code yourself; all changes go through the `coder` agent and all reviews through the `reviewer` agent. Run each agent in the foreground (`run_in_background: false`), because every step depends on the one before it.

## Setup

1. Run `git status --porcelain` and `git rev-parse HEAD` and note the result as the baseline. If the working tree is already dirty, tell me in the final summary which changes were there before the loop started.
2. Set `round = 1`.

## Loop (at most 5 rounds)

**Code step.**
- Round 1: start the `coder` agent with the full task text, and tell it to read `CLAUDE.md`, implement the task, run `./gradlew test` and `./gradlew assembleDebug`, and report back.
- Later rounds: send the reviewer's findings from the last round to the coder. Continue the same coder agent with `SendMessage` if you can, so it keeps its context. If you can't, start a new `coder` agent and give it the original task, a summary of what has been done so far, and the findings word for word. Tell it to fix every finding (or explain why a finding is wrong), re-run the tests and the build, and report back.

**Gather the changes.** After the coder reports, run `git status --porcelain` and `git diff` against the baseline yourself. Collect the list of changed and new files, and the coder's test and build results.

**Review step.** Start a new `reviewer` agent each round, so every review is independent. Give it:
- the original task,
- the list of changed and new files, plus the diff (the reviewer has no shell, so it can't run git),
- the coder's test and build results, quoted exactly,
- from round 2 on: the previous round's findings, and a request to confirm each one is resolved.

Tell it to end with a single line saying either `VERDICT: APPROVED` or `VERDICT: CHANGES REQUESTED`.

**Decide.**
- `VERDICT: APPROVED`, and the coder's latest tests and build passed: stop the loop.
- Otherwise, if `round < 5`: increment `round` and go back to the code step.
- Otherwise: stop the loop, because the round limit has been reached.

Count a missing or unclear verdict as `CHANGES REQUESTED`. A failing test or build blocks approval even when the reviewer approves; in that case, send the failure to the coder as the finding.

## Final summary for me

When the loop stops, give me a short report:
- **Outcome:** APPROVED after N rounds, or STOPPED after 5 rounds without approval.
- **What was built:** the key changes, with `file:line` links.
- **Tests and build:** the last results.
- **Review history:** one line per round with the main findings and what the coder did about them.
- **Open issues:** any findings that were never resolved or that the coder disputed, plus anything I should check by hand (for example, behaviour that needs a real device and sensors).

Do not commit, push or change branches. Leave the changes in the working tree for me to look over.
