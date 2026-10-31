"""Turns a ComfyUI workflow as saved by its web UI into the API format that /prompt accepts.

The saved format keeps widget values as positional lists and nests subgraphs; the API format wants one flat
dict of nodes with named inputs. Node definitions come from ComfyUI's /object_info.
"""

SEED_CONTROLS = {"fixed", "randomize", "increment", "decrement"}
WIDGET_TYPES = {"INT", "FLOAT", "STRING", "BOOLEAN", "COMBO", "COMFY_DYNAMICCOMBO_V3"}
UPLOADS = ("image_upload", "audio_upload", "video_upload")
FRONTEND_ONLY = {"Note", "MarkdownNote", "PrimitiveNode", "Reroute"}
MUTED, BYPASSED = 2, 4
SUBGRAPH_IN, SUBGRAPH_OUT = -10, -20


def to_api(workflow, object_info):
    """Returns (prompt, inputs): the API prompt, and for each input name of a top-level subgraph the
    (node id, input name) pairs it feeds, so callers can set "prompt", "seed" and so on by name."""
    subgraphs = {s["id"]: s for s in workflow.get("definitions", {}).get("subgraphs", [])}
    prompt, named = {}, {}

    # A source is ("node", api_id, slot), ("value", v) or None when nothing is connected.
    def expand(nodes, links, prefix, outer):
        by_id = {n["id"]: n for n in nodes}
        instances = {}

        def instance(node):
            if node["id"] not in instances:
                sub = subgraphs[node["type"]]
                widgets = dict(_widget_values(node, sub))
                linked = {slot["name"]: source(slot.get("link")) for slot in node.get("inputs", [])}
                # Matched by name: the instance may list its inputs in another order than the definition, or fewer.
                feeds = []
                for i in sub["inputs"]:
                    src = linked.get(i["name"])
                    if src is None and i["name"] in widgets:
                        src = ("value", widgets[i["name"]])
                    feeds.append(src)
                instances[node["id"]] = expand_subgraph(sub, f"{prefix}{node['id']}:", feeds)
            return instances[node["id"]]

        def source(link_id, depth=0):
            link = links.get(link_id)
            if link is None or depth > 50:
                return None
            origin, slot = link["origin_id"], link["origin_slot"]
            if origin == SUBGRAPH_IN:
                return outer["feeds"][slot] if outer and slot < len(outer["feeds"]) else None
            node = by_id.get(origin)
            if node is None or node.get("mode") == MUTED:
                return None
            if node["type"] == "Reroute" or node.get("mode") == BYPASSED:
                # Passes straight through: a reroute's only input, or a bypassed node's first input of the same type.
                out_type = (node.get("outputs") or [{}])[slot].get("type")
                for i in node.get("inputs", []):
                    if i.get("link") is not None and (node["type"] == "Reroute" or i.get("type") == out_type):
                        return source(i["link"], depth + 1)
                return None
            if node["type"] == "PrimitiveNode":
                return ("value", (node.get("widgets_values") or [None])[0])
            if node["type"] in subgraphs:
                outs = instance(node)["outputs"]
                return outs[slot] if slot < len(outs) else None
            return ("node", prefix + str(node["id"]), slot)

        for node in nodes:
            kind = node["type"]
            if kind in FRONTEND_ONLY or node.get("mode") in (MUTED, BYPASSED):
                continue
            if kind in subgraphs:
                inst = instance(node)
                if outer is None:
                    for name, targets in inst["targets"].items():
                        named.setdefault(name, []).extend(targets)
                continue
            if kind not in object_info:
                raise ValueError(f"node type '{kind}' is not installed in ComfyUI")
            api_id = prefix + str(node["id"])
            inputs = dict(_widget_values(node, object_info[kind], node_spec=True))
            for i in node.get("inputs", []):
                src = source(i.get("link"))
                if src is not None:
                    inputs[i["name"]] = [src[1], src[2]] if src[0] == "node" else src[1]
                link = links.get(i.get("link"))
                if outer is not None and link and link["origin_id"] == SUBGRAPH_IN:
                    outer["targets"].setdefault(outer["names"][link["origin_slot"]], []).append((api_id, i["name"]))
            prompt[api_id] = {"class_type": kind, "inputs": inputs, "_meta": {"title": node.get("title") or kind}}
        return source

    def expand_subgraph(sub, prefix, feeds):
        names = [i["name"] for i in sub["inputs"]]
        outer = {"feeds": feeds + [None] * (len(names) - len(feeds)), "names": names, "targets": {}}
        links = _links(sub["links"])
        source = expand(sub["nodes"], links, prefix, outer)
        outputs = []
        for out in sub["outputs"]:
            into = [l for l in out.get("linkIds", []) if links.get(l, {}).get("target_id") == SUBGRAPH_OUT]
            outputs.append(source(into[0]) if into else None)
        return {"outputs": outputs, "targets": outer["targets"]}

    expand(workflow["nodes"], _links(workflow.get("links")), "", None)
    return prompt, named


def _links(raw):
    out = {}
    for link in raw or []:
        if isinstance(link, list):  # [id, origin_id, origin_slot, target_id, target_slot, type]
            link = {"id": link[0], "origin_id": link[1], "origin_slot": link[2], "target_id": link[3], "target_slot": link[4]}
        out[link["id"]] = link
    return out


def _widget_values(node, spec, node_spec=False):
    """Pairs each widget of a saved node with its value. The widget order comes from the node definition
    (older saves only list the widgets that were turned into inputs); a subgraph instance stores its values in
    the order of the subgraph's own inputs. Seed "control after generate" and upload-button slots are skipped."""
    values = node.get("widgets_values")
    if isinstance(values, dict):
        return [(k, v) for k, v in values.items() if k != "videopreview"]
    values = list(values or [])
    out, k = [], 0

    def take(name, opts):
        nonlocal k
        if k >= len(values):
            return None
        value = values[k]
        out.append((name, value))
        k += 1
        nxt = values[k] if k < len(values) else None
        if (opts.get("control_after_generate") or "seed" in name) and isinstance(nxt, str) and nxt in SEED_CONTROLS:
            k += 1
        elif any(opts.get(u) for u in UPLOADS) and (nxt is None or nxt in ("image", "audio", "video", "")):
            k += 1
        return value

    if not node_spec:
        for i in spec["inputs"]:
            if i.get("type") in WIDGET_TYPES or "," in str(i.get("type", "")):
                take(i["name"], {})
        return out

    def walk(inputs, order, parent):
        for section in ("required", "optional"):
            for name in order.get(section, list((inputs.get(section) or {}).keys())):
                entry = (inputs.get(section) or {}).get(name)
                if entry is None:
                    continue
                kind = entry[0] if isinstance(entry, (list, tuple)) else entry
                opts = entry[1] if isinstance(entry, (list, tuple)) and len(entry) > 1 and isinstance(entry[1], dict) else {}
                if not (isinstance(kind, list) or kind in WIDGET_TYPES) or opts.get("forceInput"):
                    continue
                value = take(parent + name, opts)
                if kind == "COMFY_DYNAMICCOMBO_V3":
                    chosen = next((o for o in opts.get("options", []) if o.get("key") == value), None)
                    if chosen:
                        walk(chosen.get("inputs", {}), {}, f"{parent}{name}.")

    walk(spec.get("input", {}), spec.get("input_order", {}), "")
    return out
