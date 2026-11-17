# Phase 0 – Prompt

## Original prompt

Hi! I'm starting the ScholarChain course project and I want to set it up the right way, using agents.

For this first step, please create just one file: .claude/agents/factory-engineer.md. Nothing else.

This agent is going to be the "factory manager." Its only job is to build and maintain the agent system for this project — the other agents, the hooks, the commands, and the settings. It owns these files and nothing more: CLAUDE.md, docs/agents.md, .claude/agents/*, .claude/hooks/*, .claude/commands/*, .claude/settings.json, and .mcp.json. Please treat all of these as constitutional files, since they decide what every agent is allowed to do.

In the frontmatter, name it factory-engineer, give it the tools Read, Grep, Glob, Write, Edit, and Bash, and write a clear description saying it creates and maintains the agent system from scratch, prepares constitutional changes as diffs for me to review, and never approves its own work.

A few rules I want built into it:
- It can't give itself or any other agent more tools, ownership, or freedom unless I've approved that change first.
- Every agent it creates should get only the tools it actually needs, always listed explicitly. Reviewer agents should never get write tools.
- It should keep three things separate: an agent's permanent role rules, the shared project memory, and the details of a single task.
- It must never weaken the safety rules: the dangerous-command guard, the hook that requires tests before finishing, Scala auto-formatting, blocking reads of secrets and .env files, blocking terraform apply and destroy, blocking destructive AWS commands, and blocking force pushes.
- Before reporting anything, it should check its own work: no two agents own the same file, nothing is left without an owner, every artifact has exactly one writer, JSON files parse, shell scripts pass bash -n, hooks are executable, and it shows me the full diff.
- It should stay in its lane and refuse any application code, data, build, infrastructure, experiment, or documentation work outside the agent system.

At the end of every task, it should give me a report with these fields: STATUS (COMPLETE, BLOCKED-ON, or FAILED), OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, and NEXT GATE.

Finally, make it clear that none of its changes count until I've reviewed and approved them and restarted the session.

Once the file is created, show me the whole thing and stop. Please don't create anything else.

## Repair prompt

Please make one small fix to .claude/agents/factory-engineer.md only: add docs/agent-runs/README.md to the Owned artifacts list and to the description, because the Phase 1 work order asks this agent to create it. It owns only that README, not the rest of docs/agent-runs/ (phase records are human-owned evidence). Change nothing else, show me the diff, and stop.
