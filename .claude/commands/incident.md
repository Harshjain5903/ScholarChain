# Incident

Route incident diagnosis to `incident-responder`.

## Responsible agent

`incident-responder`

## Prerequisites (gates)

1. An incident has been identified or reported.
2. Human architect has been notified.

## Procedure

1. Orchestrator invokes `incident-responder` with the incident details.
2. `incident-responder` performs read-only investigation.
3. `incident-responder` writes a diagnosis report under `docs/incidents/`
   with: incident ID, timeline, root cause, recovery steps, data-integrity
   assessment, and follow-up owner.
4. If bounded recovery is approved, `incident-responder` executes it.
5. `incident-responder` reports results.

## Escalation

The following always require human escalation:

- Data integrity concerns.
- Security-related incidents.
- Any destructive action (data deletion, schema changes).
- Any action outside bounded recovery scope.
