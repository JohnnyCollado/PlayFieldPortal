# B7 - Niche request triage

Source: `docs/feedback/frontend-user-research.md`, section 3 ("Low-priority features"). The research
is explicit: these matter to enthusiasts and are cheap goodwill, but "nobody adopts or abandons a
launcher over them".

This plan is deliberately a triage plan, not an implementation plan. Its output is a decision per
item, so these requests stop competing for attention with B1-B5.

## The items

| Request | Source | Suggested disposition |
|---|---|---|
| Android TV / Google TV support | Daijisho #72 | Investigate. A leanback launcher is a distinct product surface, not a small feature. Decide yes/no once, publicly. |
| Samsung DeX | Daijisho #128 | Decline for now. Record the reason. |
| Dual-screen | PFP #7 | Decline for now. Record the reason. |
| FPS limiter | ES-DE #2070 | Out of scope. A launcher cannot limit another app's frame rate; explain that in the issue rather than leaving it open. |
| Font switching | ES-DE #2094 | Accept if the theme cascade from [A2](theme-module-depth-plan.md) makes it nearly free. Otherwise defer. |
| Custom clock tokens | ES-DE #2089 | Accept. Small, self-contained, fits the existing status bar. |
| Controller battery percentage | ES-DE #2088 | Accept. Small, and controller-first users are the core audience. |
| RSS / podcast | PFP backlog | Decline. Far from the product's job. |
| Gameboot videos | PFP #17 | Accept later. Strong thematic fit with the XMB identity; schedule after B1-B3. |
| Translations | PFP #2 | Accept the groundwork only: extract remaining hardcoded strings to resources now, take community translations later. |
| Extra scraper sources | Daijisho #194 | Defer until [B2](scraper-reliability-plan.md) lands. Adding sources before fixing throughput and error reporting makes the existing complaint worse. |
| Custom collection sorting | ES-DE #2069 | Accept later. Fits naturally on top of [A4](library-projection-module-plan.md). |

## Approach

1. Work the table above into a decision on each PFP issue: label it accepted, deferred, or
   declined, with a one-paragraph reason written in the issue itself.
2. For declined items, say why in terms of the product's job. "A launcher cannot control another
   app's frame rate" is a good reason; silence is not.
3. For the two accepted-and-cheap items (custom clock tokens, controller battery), implement them
   in one small batch. They are visible polish that costs little.
4. For string extraction, run a sweep for hardcoded user-facing strings in Compose sources and move
   them to resources. This is the only translation work worth doing before a translator exists.

## Risks

- The failure mode is doing these instead of B1-B5 because they are easier and produce visible
  wins. Sequence them after the reliability work.
- Declining publicly is better than silence, but tone matters. These are engaged users.

## Done when

Every item above has a written disposition, the two cheap accepted items ship, and no hardcoded
user-facing strings remain in Compose sources.
