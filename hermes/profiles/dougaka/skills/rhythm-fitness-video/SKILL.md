---
name: rhythm-fitness-video
description: Design and produce original VR-free rhythm fitness videos for screens and TVs, then publish to aozora.app and verify ATProto distribution.
---

# Rhythm fitness video lane

Use for follow-along workout videos where viewers react to music and on-screen targets or obstacles. The viewer moves in their own space; there is no VR headset or game controller.

## Format and cue design

- Make the TV-facing main cut 16:9 when the landscape render path is available. A vertical teaser is a separate edit for discovery; do not crop away a target or movement cue.
- Use a simple visual vocabulary and repeat it: left/right punch, step or lean to dodge, and rest. Place cues where they remain legible at TV distance. Give viewers enough response time and show a countdown before the first move.
- Time the cue transitions to an original or rights-cleared beat. Never use Beat Saber maps, gameplay, branding, or commercial tracks without documented permission.
- Make low-impact movements the default. Provide a pause/stop signal, identify left/right from the viewer's perspective, and avoid unsupported health, calorie, or weight-loss promises.
- Avoid relying on generated text inside a video frame. Render cue labels, arrows, timing, captions, and target overlays through a deterministic composition step when such a tool is configured. If it is not available, deliver the storyboard and timed overlay specification and report that the final video was not rendered.

## Production package

Before production, record the intended audience, intensity, duration, aspect ratio, movement vocabulary, audio source/license, and any assumptions. Produce a timed shot list, voice/cue script, overlay sheet, captions, thumbnail plan, and alt text. Check the final export for sync, readable cues, duration, aspect ratio, MIME type, playable URL, and publication-size limits. A render or a plan is not a post.

## Aozora-first release and audience learning

Prepare the main video and post for aozora.app first. Aozora's `app.aozora.embed.video` requires a directly playable `src`; `playlist` is available for HLS, and optional fields include the video blob (up to 100 MiB), MIME type, thumbnail, alt text, and aspect ratio. Verify the actual source URL or supported upload path before publishing. Use the existing Dougaka phase and approval path; no post is public unless the run has an allowed `:publish` or `:auto-publish` grant and the DougakaGovernor accepts it. The Aozora record is the AT Protocol publication artifact. Keep the record URI/CID and AppView readback as evidence, then verify Relay or downstream-client visibility separately. Aozora's embed is custom, so do not promise every ATProto client can render it.

For discovery, prepare a separate vertical teaser, clear title and description, thumbnail, and one audience question. Compare measured plays, retention, replies, reposts, and follows across releases. Report missing analytics as unmeasured; do not buy, automate, or fabricate engagement.
