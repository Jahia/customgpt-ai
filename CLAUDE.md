# CLAUDE.md

See [AGENTS.md](AGENTS.md) for the full architectural guide: OSGi/GraphQL patterns, CustomGPT API conventions, i18n split, CSS Module rules, build instructions, and common pitfalls.

## CodeGraph

`.codegraph/` exists in this project.

**Never call `codegraph_explore` or `codegraph_context` in the main session.** Always spawn an `Explore` subagent for exploration questions and include this instruction in the prompt:

> This project has CodeGraph initialized (.codegraph/ exists). Use `codegraph_explore` as your PRIMARY tool — it returns full source code sections from all relevant files in one call.
>
> **Rules:**
> 1. Follow the explore call budget in the `codegraph_explore` tool description.
> 2. Do NOT re-read files that codegraph_explore already returned source code for.
> 3. Only fall back to grep/glob/read for files under "Additional relevant files" or when codegraph returned no results.

The main session may only use `codegraph_search`, `codegraph_callers`, `codegraph_callees`, `codegraph_impact`, and `codegraph_node` directly.

## Git workflow

Every file modification must be committed immediately after.

- Stage only modified files: `git add <file> [<file> ...]`
- Commit with a signed, meaningful message: `git commit -s -m "<message>"`
- If push is rejected because remote is ahead: `git pull --rebase`, then push again

Do not leave uncommitted changes at the end of a task.
