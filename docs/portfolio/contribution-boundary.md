# Source and contribution boundary

The source project is [MoneyToad](https://github.com/rladbstn1000/MoneyToad). The reviewed original baseline is commit `c35d37e82d7273733d45100492b87f12d73fd92b`.

## Existing team-project functionality

The original project already contained SSAFY OAuth, JWT authentication, refresh storage, transaction and budget APIs, repository aggregation, card/CSV/AI integration and the frontend visual design. These are not presented as newly created individual features.

## Post-project portfolio changes

The cumulative follow-up adds ownership regression fixes, the general 404 contract, configurable CsvClient addressing, explicit demo authentication profiles, visitor sessions and HTTP boundaries, deterministic code-authored demo Chart data, single-tab frontend demo authentication, Chart data safety, lint cleanup and isolated browser verification.

This boundary describes observed source differences and the user's project context. Committer names alone are not used to infer individual team responsibility. No blanket new license is asserted over team code or visual assets. Confirmation of permitted portfolio republication remains a release gate unless separately documented by the owner.

This snapshot excludes all original Git history. Unverified source CSVs and the porting PDF remain outside it. Historic credentials are neither copied nor reproduced. Their revocation status is an external owner check, not something inferred from absence in this snapshot.

The exact per-file provenance and final inclusion decisions are in [the file manifest](final-manifest.md). Commit 1 imports reviewed HEAD bytes and adds only the public root ignore policy. Later cumulative snapshots separate personal follow-up by dependency; environment examples are introduced with the relevant follow-up configuration. Shared files have multiple recorded transitions, so their final filename alone is not used to claim authorship.
