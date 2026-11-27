// Offline check for the patched xai plugin: real assertions, no network.
import { readFileSync } from "node:fs";

const source = readFileSync(new URL("./plugin.js", import.meta.url), "utf8");
const mod = await import("data:text/javascript;base64," + Buffer.from(source).toString("base64"));

let failed = 0;
function check(name, fn) {
  try {
    fn();
    console.log("PASS  " + name);
  } catch (error) {
    failed += 1;
    console.log("FAIL  " + name + "  -> " + error.message);
  }
}
function eq(actual, expected, label) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) throw new Error((label || "") + " expected " + e + " got " + a);
}

const done = (url) => ({ status: "done", video: { url, duration: 6, respect_moderation: true } });

check("absolute xAI link stays credentialless and untouched", () => {
  const out = mod.buildContentRequest({
    artifactKey: "video",
    data: done("https://vidgen.x.ai/v.mp4"),
    baseUrl: "https://api.x.ai",
    apiKey: "sk-xai",
    clientRequest: { method: "GET" },
  });
  eq(out.url, "https://vidgen.x.ai/v.mp4");
  eq(out.credentialless, true);
  eq(out.headers, undefined);
});

check("relative gateway path re-points at the gateway's own artifact route", () => {
  const out = mod.buildContentRequest({
    artifactKey: "video",
    data: done("/v1/videos/a8a0-uuid/content"),
    baseUrl: "https://api.example.com",
    apiKey: "sk-demo",
    taskId: "task_9IG5A6wOMaYteKK9eqsZSMBFNa7svBp1",
    clientRequest: { method: "GET" },
  });
  eq(out.url, "https://api.example.com/v1/videos/task_9IG5A6wOMaYteKK9eqsZSMBFNa7svBp1/content");
  eq(out.credentialless, undefined);
  eq(out.headers, { Accept: "*/*", Authorization: "Bearer sk-demo" });
  eq(out.mimeType, "video/mp4");
});

check("the upstream uuid in the path is never reused as the content id", () => {
  // A gateway addresses its content route by its own public task id; the uuid
  // xAI handed it answers 404. A regression here is invisible offline, so the
  // assertion is explicit.
  const out = mod.buildContentRequest({
    artifactKey: "video",
    data: done("/v1/videos/f665cbf9-8ad0-9353-b00f-6fc620e9c597/content"),
    baseUrl: "https://api.example.com",
    apiKey: "k",
    taskId: "task_knUzWpCxtocfoqqvCz1INxiOiufG3956",
    clientRequest: { method: "GET" },
  });
  if (out.url.includes("f665cbf9")) throw new Error("reused the upstream uuid: " + out.url);
  eq(out.url, "https://api.example.com/v1/videos/task_knUzWpCxtocfoqqvCz1INxiOiufG3956/content");
});

check("trailing slash on base does not double up", () => {
  const out = mod.buildContentRequest({
    artifactKey: "video",
    data: done("/v1/videos/x/content"),
    baseUrl: "https://api.example.com/",
    apiKey: "k",
    taskId: "task_abc",
    clientRequest: { method: "GET" },
  });
  eq(out.url, "https://api.example.com/v1/videos/task_abc/content");
});

check("base with a path prefix is preserved", () => {
  const out = mod.buildContentRequest({
    artifactKey: "video",
    data: done("v1/videos/x/content"),
    baseUrl: "https://gw.example.com/openai",
    apiKey: "k",
    taskId: "task_abc",
    clientRequest: { method: "GET" },
  });
  eq(out.url, "https://gw.example.com/openai/v1/videos/task_abc/content");
});

check("HEAD from the client is forwarded", () => {
  const out = mod.buildContentRequest({
    artifactKey: "video",
    data: done("/v1/videos/x/content"),
    baseUrl: "https://api.example.com",
    apiKey: "k",
    taskId: "task_abc",
    clientRequest: { method: "HEAD" },
  });
  eq(out.method, "HEAD");
});

check("missing key on a relative path fails loudly instead of a silent 410", () => {
  let threw = false;
  try {
    mod.buildContentRequest({
      artifactKey: "video",
      data: done("/v1/videos/x/content"),
      baseUrl: "https://api.example.com",
      apiKey: "",
      taskId: "task_abc",
      clientRequest: { method: "GET" },
    });
  } catch (error) {
    threw = /requires the channel base URL, key and task id/.test(error.message);
  }
  if (!threw) throw new Error("should have thrown");
});

check("missing task id on a relative path fails loudly", () => {
  let threw = false;
  try {
    mod.buildContentRequest({
      artifactKey: "video",
      data: done("/v1/videos/x/content"),
      baseUrl: "https://api.example.com",
      apiKey: "k",
      clientRequest: { method: "GET" },
    });
  } catch (error) {
    threw = /requires the channel base URL, key and task id/.test(error.message);
  }
  if (!threw) throw new Error("should have thrown");
});

check("moderated video still reports artifact_not_found", () => {
  let threw = false;
  try {
    mod.buildContentRequest({
      artifactKey: "video",
      data: { status: "done", video: { url: "/v1/videos/x/content", respect_moderation: false } },
      baseUrl: "https://api.example.com",
      apiKey: "k",
      clientRequest: { method: "GET" },
    });
  } catch (error) {
    threw = error.message === "artifact_not_found";
  }
  if (!threw) throw new Error("should have thrown");
});

check("not-yet-done task reports artifact_not_found", () => {
  let threw = false;
  try {
    mod.buildContentRequest({
      artifactKey: "video",
      data: { status: "pending", video: {} },
      baseUrl: "https://api.example.com",
      apiKey: "k",
      clientRequest: { method: "GET" },
    });
  } catch (error) {
    threw = error.message === "artifact_not_found";
  }
  if (!threw) throw new Error("should have thrown");
});

check("meta still validates and keeps the factory routes/version", () => {
  const meta = mod.meta;
  eq(meta.key, "xai");
  eq(meta.version, "1.1.5");
  eq(meta.apiVersion, 1);
  eq(meta.allowedHosts, ["vidgen.x.ai"]);
  eq(meta.routes.map((r) => r.method + " " + r.path), [
    "POST /xai/v1/videos/generations",
    "POST /xai/v1/videos/edits",
    "POST /xai/v1/videos/extensions",
    "GET /xai/v1/videos/:request_id",
  ]);
});

check("listArtifacts only exposes the video for a finished task", () => {
  eq(mod.listArtifacts({ status: "SUCCESS", data: done("/v1/videos/x/content") }), [
    { key: "video", type: "video", mimeType: "video/mp4" },
  ]);
  eq(mod.listArtifacts({ status: "IN_PROGRESS", data: {} }), []);
});

// The native query route is what a caller actually polls, so the download URL
// has to be right there: buildContentRequest alone never runs for a caller that
// downloads the URL it was given.
const finishedTask = (url) => ({
  status: "SUCCESS",
  task_id: "task_9IG5A6wOMaYteKK9eqsZSMBFNa7svBp1",
  data: done(url),
});
// A query renderer receives only path/params/headers plus the host-projected
// artifacts; it has no channel base URL and no public task id of its own.
const nativeCtx = {
  path: "/xai/v1/videos/task_9IG5A6wOMaYteKK9eqsZSMBFNa7svBp1",
  params: { request_id: "task_9IG5A6wOMaYteKK9eqsZSMBFNa7svBp1" },
  artifacts: { video: { key: "video", type: "video", url: "https://relay.example/v1/tasks/task_9IG5A6wOMaYteKK9eqsZSMBFNa7svBp1/artifacts/video/content?sig=x" } },
};

check("native status swaps the gateway path for the host artifact URL", () => {
  const out = mod.native.videoStatus(
    nativeCtx,
    finishedTask("/v1/videos/f665cbf9-8ad0-9353-b00f-6fc620e9c597/content"),
  );
  eq(out.video.url, nativeCtx.artifacts.video.url);
  eq(out.video.duration, 6);
  eq(out.video.respect_moderation, true);
});

check("native status leaves an absolute CDN link alone", () => {
  const out = mod.native.videoStatus(nativeCtx, finishedTask("https://vidgen.x.ai/v.mp4"));
  eq(out.video.url, "https://vidgen.x.ai/v.mp4");
});

check("native status without an artifact leaves the path as it came", () => {
  const out = mod.native.videoStatus({ params: {} }, finishedTask("/v1/videos/u/content"));
  eq(out.video.url, "/v1/videos/u/content");
});

check("native status never reuses the upstream uuid from the path", () => {
  const out = mod.native.videoStatus(nativeCtx, finishedTask("/v1/videos/f665cbf9-8ad0/content"));
  if (out.video.url.includes("f665cbf9")) throw new Error("used the upstream id: " + out.video.url);
  eq(out.video.url, nativeCtx.artifacts.video.url);
});

check("native status keeps the moderated shape and drops the upstream cost", () => {
  const out = mod.native.videoStatus(nativeCtx, {
    status: "SUCCESS",
    task_id: "task_x",
    data: { status: "done", usage: { cost_in_usd_ticks: 1 }, video: { respect_moderation: false } },
  });
  eq(out.video, { respect_moderation: false });
  eq(out.usage, undefined);
  eq(out.request_id, undefined);
});

console.log(failed === 0 ? "\nALL PASS" : "\n" + failed + " FAILED");
process.exit(failed === 0 ? 0 : 1);
