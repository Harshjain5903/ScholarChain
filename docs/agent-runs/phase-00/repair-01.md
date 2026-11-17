# Phase 0 – Repair Record

**Issue:** `docs/agent-runs/README.md` was missing from factory-engineer's owned artifacts and description. The Phase 1 work order asks factory-engineer to create that file, so without this fix the agent would return BLOCKED-ON.

**Found by:** factory-engineer's own notes in its first reply.

**Fix:** Added `docs/agent-runs/README.md` to the description and the Owned artifacts list, with a note that it owns only that README — not the rest of `docs/agent-runs/`, which is human-owned phase evidence.

**Result:** Reviewed the diff. Only the two expected lines changed. Accepted.
