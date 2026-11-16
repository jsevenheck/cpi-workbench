---
title: Review Trail word matches without rewriting unrelated fidelity policy
author: Hermes
date: 2026-11-16
branch: claude/great-cannon-v2nd6h
id: l-5cb26b0c
scope: repo
status: active
src: agent
touches:
  - CHANGELOG.md
  - docs/fidelity.md
  - docs/plan.md
  - docs/status.md
---

The six branch-wide trail check conflicts at this checkpoint are lexical matches, not reintroductions of rejected behavior: CHANGELOG.md's script-collection import sentence is neither Contiva decoding nor Gather failure policy; docs/status.md's Flow Steps row describes binary bodies and supported formats, not Contiva. docs/fidelity.md's Encoder/Decoder row documents the separate Camel data-format assumption (Q-E1), not Gather Combine at XPath. docs/plan.md F62 lists imported step keys; F63 records documented settings and open Q-E1 questions, not the old open W2 implementation. Preserve the Gather policy (warn for non-XML input) and Contiva refusal. Document the interpretation in the PR/commit rather than changing correct prose to evade word matching. The default checker warns that this large branch diff is truncated at 400 KB, so also run trail check --base e92d613 for full coverage of this follow-up diff.

That scoped check reports three lexical conflicts: two in the new diagram E2E block, which copies/imports another archive within the already-open disposable project (no project.open call and no project switch); one on the package-import status row, which still does not implement Contiva decoding. Review these as separate matches rather than assuming the branch-wide checker covered this follow-up after its truncation.
