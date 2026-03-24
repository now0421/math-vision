import ast
import json
import os
import sys
import traceback
import types
from collections import Counter
from pathlib import Path

import manim
import numpy as np
from manim import config

_GEOMETRY_PATH_ENV = "AUTOMANIM_GEOMETRY_PATH"
_EPSILON = 1e-9
_ROUND_DIGITS = 6
_LONG_PLAY_SECONDS = 2.0
_MEDIUM_PLAY_SECONDS = 0.75
_PRIORITY_ANIMATION_TYPES = {
    "FadeIn",
    "FadeOut",
    "Transform",
    "ReplacementTransform",
    "TransformFromCopy",
    "FadeTransform",
    "FadeTransformPieces",
    "TransformMatchingShapes",
    "TransformMatchingTex",
    "_MethodAnimation",
    "MoveToTarget",
}
_RESULT_ONLY_ANIMATION_TYPES = {
    "Create",
    "Uncreate",
    "Write",
    "Unwrite",
    "FadeIn",
    "FadeOut",
    "GrowFromCenter",
    "GrowFromEdge",
    "GrowFromPoint",
    "GrowArrow",
    "DrawBorderThenFill",
}
_PRE_REMOVAL_ANIMATION_TYPES = {
    "FadeOut",
    "Uncreate",
    "Unwrite",
    "ShrinkToCenter",
    "FadeTransform",
    "FadeTransformPieces",
    "ReplacementTransform",
    "RemoveTextLetterByLetter",
}
_SEMANTIC_NAME_SOURCE_RANK = {
    "class_name": 0,
    "display_text": 1,
    "mobject_name": 2,
    "derived_path": 3,
    "source_argument": 4,
}
_SOURCE_AST_CACHE = {}
_TEXTUAL_CLASSES = {"text", "formula"}
_PRIMITIVE_LAYOUT_CLASSES = {"text", "formula", "point", "line", "shape"}
_COMPOSITE_GROUP_CLASSES = {"VGroup", "Group"}
_LAYOUT_OFFSCREEN_TOLERANCE = 0.03
_LAYOUT_MIN_OVERLAP_AREA = 0.015
_LAYOUT_MIN_OVERLAP_RATIO = 0.08
_NON_VISUAL_MOBJECT_CLASSES = {
    "Mobject",
    "_AnimationBuilder",
    "ValueTracker",
    "ComplexValueTracker",
}


def patch_scene_for_geometry_export(scene_cls):
    geometry_path = os.environ.get(_GEOMETRY_PATH_ENV)
    if not geometry_path or getattr(scene_cls, "_automanim_geometry_export_patched", False):
        return scene_cls

    scene_source_path = _resolve_scene_source_path(scene_cls)
    helper_source_path = Path(__file__).resolve()

    class AutoManimGeometryExportScene(scene_cls):
        def setup(self):
            self._automanim_geometry_path = geometry_path
            self._automanim_geometry_export_error = None
            self._automanim_scene_source_path = scene_source_path
            self._automanim_helper_source_path = helper_source_path
            self._automanim_play_index = 0
            self._automanim_sample_index = 0
            self._automanim_observed_render_frame_count = 0
            self._automanim_samples = []
            self._automanim_play_records = []
            self._automanim_current_play = None
            self._automanim_object_registry = {}
            self._automanim_object_ids = {}
            self._automanim_next_object_number = 1
            self._automanim_install_geometry_hook()
            super().setup()

        def add(self, *mobjects):
            source_hint = self._automanim_resolve_source_hint()
            semantic_hints = _extract_positional_semantic_hints(
                source_hint=source_hint,
                scene_source_path=self._automanim_scene_source_path,
                method_names={"add"},
                arg_count=len(mobjects),
            )
            self._automanim_register_roots(mobjects, source_hint, semantic_hints=semantic_hints)
            return super().add(*mobjects)

        def add_foreground_mobjects(self, *mobjects):
            source_hint = self._automanim_resolve_source_hint()
            semantic_hints = _extract_positional_semantic_hints(
                source_hint=source_hint,
                scene_source_path=self._automanim_scene_source_path,
                method_names={"add_foreground_mobjects"},
                arg_count=len(mobjects),
            )
            self._automanim_register_roots(mobjects, source_hint, semantic_hints=semantic_hints)
            return super().add_foreground_mobjects(*mobjects)

        def add_fixed_in_frame_mobjects(self, *mobjects):
            source_hint = self._automanim_resolve_source_hint()
            semantic_hints = _extract_positional_semantic_hints(
                source_hint=source_hint,
                scene_source_path=self._automanim_scene_source_path,
                method_names={"add_fixed_in_frame_mobjects"},
                arg_count=len(mobjects),
            )
            self._automanim_register_roots(mobjects, source_hint, semantic_hints=semantic_hints)
            parent_method = getattr(
                super(AutoManimGeometryExportScene, self),
                "add_fixed_in_frame_mobjects",
                None,
            )
            if callable(parent_method):
                return parent_method(*mobjects)
            return self

        def play(
            self,
            *args,
            subcaption=None,
            subcaption_duration=None,
            subcaption_offset=0,
            **kwargs,
        ):
            source_hint = self._automanim_resolve_source_hint()
            self._automanim_play_index += 1
            play_argument_hints = _extract_play_argument_hints(
                source_hint=source_hint,
                scene_source_path=self._automanim_scene_source_path,
                arg_count=len(args),
            )
            animation_summary = _summarize_play_args(args, play_argument_hints)
            priority_sampling = _is_priority_animation_summary(animation_summary)
            pre_removal_descriptors = _pre_removal_descriptors(args, play_argument_hints)
            play_record = {
                "play_index": self._automanim_play_index,
                "play_type": _detect_play_type(args),
                "scene_method": _source_value(source_hint, "method_name"),
                "source_line": _source_value(source_hint, "line_number"),
                "source_code": _source_value(source_hint, "code_line"),
                "animation_summary": animation_summary,
                "priority_sampling": priority_sampling,
                "sampling_profile": None,
                "start_scene_time_seconds": _safe_float(getattr(self, "time", 0.0)),
                "end_scene_time_seconds": None,
                "duration_seconds": None,
                "observed_render_frame_count": 0,
                "sampled_roles": [],
                "sample_ids": [],
                "visibility_change_count": 0,
                "visibility_change_sample_ids": [],
                "expected_removed_object_ids": [],
                "_last_visible_signature": None,
            }
            self._automanim_play_records.append(play_record)
            self._automanim_current_play = play_record

            self._automanim_register_targets_from_play_args(
                args,
                source_hint,
                play_argument_hints=play_argument_hints,
            )
            play_record["expected_removed_object_ids"] = self._automanim_expected_removed_object_ids(
                pre_removal_descriptors,
                source_hint=source_hint,
                play_index=play_record["play_index"],
                scene_time=getattr(self, "time", 0.0),
            )

            try:
                start_collected = self._automanim_collect_visible_objects(
                    source_hint=source_hint,
                    play_index=play_record["play_index"],
                    scene_time=getattr(self, "time", 0.0),
                )
                play_record["_last_visible_signature"] = start_collected["visible_signature"]
                if (
                    play_record["play_type"] != "wait"
                    and _has_pre_removal_animation_summary(animation_summary)
                ):
                    pre_removal_collected = self._automanim_collect_visible_objects(
                        source_hint=source_hint,
                        play_index=play_record["play_index"],
                        scene_time=getattr(self, "time", 0.0),
                        extra_descriptors=pre_removal_descriptors,
                    )
                    self._automanim_capture_sample(
                        sample_role="pre_removal",
                        trigger="play_pre_removal",
                        animation_time=0.0,
                        scene_time=getattr(self, "time", 0.0),
                        source_hint=source_hint,
                        precollected=pre_removal_collected,
                    )

                return super().play(
                    *args,
                    subcaption=subcaption,
                    subcaption_duration=subcaption_duration,
                    subcaption_offset=subcaption_offset,
                    **kwargs,
                )
            finally:
                current_play = self._automanim_current_play
                if current_play is not None:
                    duration = _safe_float(getattr(self, "duration", None))
                    current_play["duration_seconds"] = duration
                    current_play["end_scene_time_seconds"] = _safe_float(getattr(self, "time", None))
                    final_collected = self._automanim_collect_visible_objects(
                        source_hint=source_hint,
                        play_index=current_play["play_index"],
                        scene_time=getattr(self, "time", 0.0),
                    )
                    final_visibility_event = self._automanim_detect_visibility_change(
                        current_play=current_play,
                        visible_signature=final_collected["visible_signature"],
                    )
                    if final_visibility_event is not None:
                        sample = self._automanim_capture_sample(
                            sample_role=f"visibility_removed_{current_play['visibility_change_count'] + 1}",
                            trigger="play_end_visibility_removed",
                            animation_time=duration,
                            scene_time=getattr(self, "time", 0.0),
                            source_hint=source_hint,
                            precollected=final_collected,
                        )
                        self._automanim_note_visibility_change(
                            current_play,
                            sample,
                            final_visibility_event,
                        )

                    self._automanim_current_play = None

        def _automanim_install_geometry_hook(self):
            if getattr(self, "_automanim_geometry_hook_installed", False):
                return

            original_render = self.renderer.render

            def wrapped_render(renderer_self, scene, time, moving_mobjects=None):
                if scene is self:
                    try:
                        self._automanim_observed_render_frame_count += 1
                        self._automanim_handle_render_frame(
                            renderer_self=renderer_self,
                            animation_time=time,
                        )
                    except Exception as exc:
                        self._automanim_note_geometry_error(exc)
                return original_render(scene, time, moving_mobjects)

            self.renderer.render = types.MethodType(wrapped_render, self.renderer)
            self._automanim_geometry_hook_installed = True

        def _automanim_handle_render_frame(self, renderer_self, animation_time):
            current_play = self._automanim_current_play
            if current_play is None:
                return

            current_play["observed_render_frame_count"] += 1

            capture_source_hint = current_play_source_hint(current_play)
            scene_time = _safe_float(getattr(renderer_self, "time", 0.0))
            collected = self._automanim_collect_visible_objects(
                source_hint=capture_source_hint,
                play_index=current_play["play_index"],
                scene_time=scene_time,
            )
            visibility_event = self._automanim_detect_visibility_change(
                current_play=current_play,
                visible_signature=collected["visible_signature"],
            )

            if visibility_event is not None:
                sample = self._automanim_capture_sample(
                    sample_role=f"visibility_removed_{current_play['visibility_change_count'] + 1}",
                    trigger="visibility_removed",
                    animation_time=animation_time,
                    scene_time=scene_time,
                    source_hint=capture_source_hint,
                    precollected=collected,
                )
                self._automanim_note_visibility_change(current_play, sample, visibility_event)

        def _automanim_note_geometry_error(self, exc):
            if self._automanim_geometry_export_error is None:
                self._automanim_geometry_export_error = f"{type(exc).__name__}: {exc}"

        def _automanim_resolve_source_hint(self):
            return _resolve_source_hint(
                scene_source_path=self._automanim_scene_source_path,
                helper_source_path=self._automanim_helper_source_path,
            )

        def _automanim_register_roots(self, mobjects, source_hint, semantic_hints=None):
            scene_time = _safe_float(getattr(self, "time", 0.0))
            play_index = self._automanim_play_index or None
            semantic_hints = semantic_hints or []
            for index, mobject in enumerate(mobjects):
                if not _should_track_object(mobject):
                    continue
                semantic_hint = semantic_hints[index] if index < len(semantic_hints) else None
                self._automanim_register_mobject(
                    mobject,
                    source_hint=source_hint,
                    play_index=play_index,
                    scene_time=scene_time,
                    semantic_hint=semantic_hint,
                    semantic_hint_source="source_argument",
                )

        def _automanim_register_targets_from_play_args(self, args, source_hint, play_argument_hints=None):
            scene_time = _safe_float(getattr(self, "time", 0.0))
            play_index = self._automanim_play_index or None
            for descriptor in _iter_target_descriptors(args, play_argument_hints=play_argument_hints):
                mobject = descriptor["mobject"]
                if not _should_track_object(mobject):
                    continue
                self._automanim_register_mobject(
                    mobject,
                    source_hint=source_hint,
                    play_index=play_index,
                    scene_time=scene_time,
                    semantic_hint=descriptor.get("semantic_name"),
                    semantic_hint_source="source_argument",
                )

        def _automanim_expected_removed_object_ids(
            self,
            descriptors,
            source_hint,
            play_index,
            scene_time,
        ):
            expected_ids = []
            seen_ids = set()
            for descriptor in descriptors or []:
                mobject = descriptor.get("mobject")
                if not _should_track_object(mobject):
                    continue
                registry_entry = self._automanim_register_mobject(
                    mobject,
                    source_hint=source_hint,
                    play_index=play_index,
                    scene_time=_safe_float(scene_time),
                    semantic_hint=descriptor.get("semantic_name"),
                    semantic_hint_source="source_argument",
                )
                stable_id = registry_entry.get("stable_id") if registry_entry is not None else None
                if stable_id is None or stable_id in seen_ids:
                    continue
                seen_ids.add(stable_id)
                expected_ids.append(stable_id)
            return expected_ids

        def _automanim_register_mobject(
            self,
            mobject,
            source_hint,
            play_index,
            scene_time,
            semantic_hint=None,
            semantic_hint_source=None,
        ):
            object_id = id(mobject)
            stable_id = self._automanim_object_ids.get(object_id)
            semantic_name, semantic_name_source = _resolve_semantic_name(
                mobject,
                semantic_hint=semantic_hint,
                semantic_hint_source=semantic_hint_source,
            )
            if stable_id is None:
                stable_id = f"mob-{self._automanim_next_object_number:04d}"
                self._automanim_next_object_number += 1
                self._automanim_object_ids[object_id] = stable_id
                self._automanim_object_registry[stable_id] = {
                    "stable_id": stable_id,
                    "name": _mobject_name(mobject),
                    "semantic_name": semantic_name,
                    "semantic_name_source": semantic_name_source,
                    "class_name": mobject.__class__.__name__,
                    "semantic_class": _semantic_class(mobject),
                    "display_text": _display_text(mobject),
                    "created_in_method": _source_value(source_hint, "method_name"),
                    "created_at_line": _source_value(source_hint, "line_number"),
                    "created_from": _source_value(source_hint, "code_line"),
                    "first_seen_play_index": play_index,
                    "first_seen_scene_time_seconds": scene_time,
                    "last_seen_play_index": play_index,
                    "last_seen_scene_time_seconds": scene_time,
                }
            else:
                entry = self._automanim_object_registry[stable_id]
                entry["last_seen_play_index"] = play_index
                entry["last_seen_scene_time_seconds"] = scene_time
                (
                    entry["semantic_name"],
                    entry["semantic_name_source"],
                ) = _prefer_semantic_name(
                    current_name=entry.get("semantic_name"),
                    current_source=entry.get("semantic_name_source"),
                    candidate_name=semantic_name,
                    candidate_source=semantic_name_source,
                )

            return self._automanim_object_registry[stable_id]

        def _automanim_collect_visible_objects(
            self,
            source_hint,
            play_index,
            scene_time,
            extra_descriptors=None,
        ):
            roots = _unique_roots(self)
            foreground_ids = {id(mobject) for mobject in getattr(self, "foreground_mobjects", [])}
            elements = []
            visible_object_ids = []
            seen_object_ids = set()
            sample_order = 0
            for mobject in roots:
                if not _should_track_object(mobject):
                    continue
                object_id = id(mobject)
                if object_id in seen_object_ids:
                    continue
                root_entry = self._automanim_register_mobject(
                    mobject,
                    source_hint=source_hint,
                    play_index=play_index,
                    scene_time=_safe_float(scene_time),
                )
                added = self._automanim_collect_layout_elements(
                    mobject=mobject,
                    registry_entry=root_entry,
                    source_hint=source_hint,
                    play_index=play_index,
                    scene_time=scene_time,
                    foreground_ids=foreground_ids,
                    elements=elements,
                    visible_object_ids=visible_object_ids,
                    top_level_entry=root_entry,
                    top_level_order=sample_order,
                    element_path=str(sample_order),
                    require_visible=True,
                )
                if added:
                    seen_object_ids.add(object_id)
                    sample_order += 1

            for descriptor in extra_descriptors or []:
                mobject = descriptor.get("mobject")
                if not _should_track_object(mobject):
                    continue
                object_id = id(mobject)
                if object_id in seen_object_ids:
                    continue
                extra_entry = self._automanim_register_mobject(
                    mobject,
                    source_hint=source_hint,
                    play_index=play_index,
                    scene_time=_safe_float(scene_time),
                    semantic_hint=descriptor.get("semantic_name"),
                    semantic_hint_source="source_argument",
                )
                added = self._automanim_collect_layout_elements(
                    mobject=mobject,
                    registry_entry=extra_entry,
                    source_hint=source_hint,
                    play_index=play_index,
                    scene_time=scene_time,
                    foreground_ids=foreground_ids,
                    elements=elements,
                    visible_object_ids=visible_object_ids,
                    top_level_entry=extra_entry,
                    top_level_order=sample_order,
                    element_path=str(sample_order),
                    require_visible=False,
                )
                if added:
                    seen_object_ids.add(object_id)
                    sample_order += 1

            return {
                "elements": elements,
                "visible_object_ids": visible_object_ids,
                "visible_signature": tuple(sorted(visible_object_ids)),
            }

        def _automanim_collect_layout_elements(
            self,
            mobject,
            registry_entry,
            source_hint,
            play_index,
            scene_time,
            foreground_ids,
            elements,
            visible_object_ids,
            top_level_entry,
            top_level_order,
            element_path,
            require_visible,
        ):
            record = _build_semantic_mobject_record(
                mobject=mobject,
                registry_entry=registry_entry,
                foreground_ids=foreground_ids,
                camera=getattr(self, "camera", None),
                require_visible=require_visible,
            )
            if record is None:
                return False

            child_descriptors = []
            for child_index, child in enumerate(getattr(mobject, "submobjects", []) or []):
                if not _should_track_object(child):
                    continue
                child_descriptors.append((child_index, child))

            if _should_descend_layout_children(mobject, child_descriptors):
                before_count = len(elements)
                for child_index, child in child_descriptors:
                    child_entry = self._automanim_register_mobject(
                        child,
                        source_hint=source_hint,
                        play_index=play_index,
                        scene_time=_safe_float(scene_time),
                        semantic_hint=_derived_semantic_name(
                            parent_entry=registry_entry,
                            child_index=child_index,
                        ),
                        semantic_hint_source="derived_path",
                    )
                    self._automanim_collect_layout_elements(
                        mobject=child,
                        registry_entry=child_entry,
                        source_hint=source_hint,
                        play_index=play_index,
                        scene_time=scene_time,
                        foreground_ids=foreground_ids,
                        elements=elements,
                        visible_object_ids=visible_object_ids,
                        top_level_entry=top_level_entry,
                        top_level_order=top_level_order,
                        element_path=f"{element_path}.{child_index}",
                        require_visible=False,
                    )

                if len(elements) > before_count:
                    return True

            record["element_path"] = element_path
            record["parent_element_path"] = element_path.rsplit(".", 1)[0] if "." in element_path else None
            record["top_level_stable_id"] = top_level_entry["stable_id"]
            record["top_level_semantic_name"] = top_level_entry.get("semantic_name")
            record["top_level_order"] = top_level_order
            record["sample_order"] = len(elements)
            elements.append(record)
            if record.get("visible"):
                visible_object_ids.append(registry_entry["stable_id"])
            return True

        def _automanim_detect_visibility_change(self, current_play, visible_signature):
            previous_signature = current_play.get("_last_visible_signature")
            if previous_signature is None:
                current_play["_last_visible_signature"] = visible_signature
                return None
            if previous_signature == visible_signature:
                return None

            current_play["_last_visible_signature"] = visible_signature
            added_ids = [object_id for object_id in visible_signature if object_id not in previous_signature]
            removed_ids = [object_id for object_id in previous_signature if object_id not in visible_signature]
            if not removed_ids:
                return None
            expected_removed_ids = current_play.get("expected_removed_object_ids") or []
            if expected_removed_ids:
                removed_ids = [object_id for object_id in removed_ids if object_id in expected_removed_ids]
                if not removed_ids:
                    return None
            else:
                return None
            return {
                "added_visible_ids": added_ids,
                "removed_visible_ids": removed_ids,
                "added_objects": _object_refs_from_ids(self._automanim_object_registry, added_ids),
                "removed_objects": _object_refs_from_ids(
                    self._automanim_object_registry,
                    removed_ids,
                ),
            }

        def _automanim_note_visibility_change(self, current_play, sample, visibility_event):
            if current_play is None or sample is None or visibility_event is None:
                return

            current_play["visibility_change_count"] += 1
            sample_id = sample.get("sample_id")
            if sample_id is not None:
                current_play["visibility_change_sample_ids"].append(sample_id)

        def _automanim_capture_sample(
            self,
            sample_role,
            trigger,
            animation_time,
            scene_time,
            source_hint,
            precollected=None,
        ):
            current_play = self._automanim_current_play
            play_index = current_play["play_index"] if current_play is not None else None

            if current_play is not None and sample_role in current_play["sampled_roles"]:
                return None

            collected = precollected
            if collected is None:
                collected = self._automanim_collect_visible_objects(
                    source_hint=source_hint,
                    play_index=play_index,
                    scene_time=scene_time,
                )

            self._automanim_sample_index += 1
            sample_id = f"sample-{self._automanim_sample_index:04d}"
            sample = {
                "sample_id": sample_id,
                "play_index": play_index,
                "play_type": current_play["play_type"] if current_play is not None else "scene",
                "sample_role": sample_role,
                "scene_method": _source_value(source_hint, "method_name"),
                "source_line": _source_value(source_hint, "line_number"),
                "source_code": _source_value(source_hint, "code_line"),
                "animation_time_seconds": _safe_float(animation_time),
                "scene_time_seconds": _safe_float(scene_time),
                "trigger": trigger,
                "element_count": len(collected["elements"]),
                "elements": collected["elements"],
            }
            self._automanim_samples.append(sample)

            if current_play is not None:
                current_play["sampled_roles"].append(sample_role)
                current_play["sample_ids"].append(sample_id)

            return sample

        def _automanim_write_geometry_file(self):
            payload = {
                "scene_name": self.__class__.__name__,
                "report_type": "sampled_frame_elements_report",
                "report_version": 6,
                "manim_version": getattr(manim, "__version__", None),
                "frame_size": {
                    "width": _safe_float(config["frame_width"]),
                    "height": _safe_float(config["frame_height"]),
                    "x_radius": _safe_float(config["frame_x_radius"]),
                    "y_radius": _safe_float(config["frame_y_radius"]),
                },
                "frame_bounds": _frame_bounds_from_config(),
                "element_bounds_space": "screen",
                "sample_count": len(self._automanim_samples),
                "samples": self._automanim_samples,
            }
            if self._automanim_geometry_export_error is not None:
                payload["export_error"] = self._automanim_geometry_export_error

            output_path = Path(self._automanim_geometry_path)
            output_path.parent.mkdir(parents=True, exist_ok=True)
            output_path.write_text(
                json.dumps(payload, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )

        def render(self, preview=False):
            try:
                return super().render(preview)
            finally:
                try:
                    final_scene_time = _safe_float(getattr(self, "time", 0.0))
                    final_source_hint = (
                        current_play_source_hint(self._automanim_play_records[-1])
                        if self._automanim_play_records
                        else self._automanim_resolve_source_hint()
                    )
                    final_collected = self._automanim_collect_visible_objects(
                        source_hint=final_source_hint,
                        play_index=self._automanim_play_index or None,
                        scene_time=final_scene_time,
                    )
                    if not _sample_matches_scene_state(
                        self._automanim_samples[-1] if self._automanim_samples else None,
                        final_collected,
                        final_scene_time,
                    ):
                        self._automanim_capture_sample(
                            sample_role="scene_final",
                            trigger="scene_final",
                            animation_time=None,
                            scene_time=final_scene_time,
                            source_hint=final_source_hint,
                            precollected=final_collected,
                        )
                    self._automanim_write_geometry_file()
                except Exception as exc:
                    self._automanim_note_geometry_error(exc)
                    try:
                        self._automanim_write_geometry_file()
                    except Exception:
                        pass

    AutoManimGeometryExportScene.__name__ = scene_cls.__name__
    AutoManimGeometryExportScene.__qualname__ = scene_cls.__qualname__
    AutoManimGeometryExportScene.__module__ = scene_cls.__module__
    AutoManimGeometryExportScene.__doc__ = scene_cls.__doc__
    AutoManimGeometryExportScene._automanim_geometry_export_patched = True
    return AutoManimGeometryExportScene


def current_play_source_hint(play_record):
    if play_record is None:
        return None
    return {
        "method_name": play_record.get("scene_method"),
        "line_number": play_record.get("source_line"),
        "code_line": play_record.get("source_code"),
    }


def _sample_matches_scene_state(sample, collected, scene_time):
    if sample is None or collected is None:
        return False

    sample_time = _safe_float(sample.get("scene_time_seconds"))
    target_time = _safe_float(scene_time)
    frame_tolerance = max(1.0 / max(_safe_float(config["frame_rate"]) or 1.0, 1.0), 0.01)
    if sample_time is None or target_time is None:
        time_matches = True
    else:
        time_matches = abs(sample_time - target_time) <= frame_tolerance
    if not time_matches:
        return False

    sample_visible_ids = sorted(
        {
            element.get("stable_id")
            for element in sample.get("elements", [])
            if element.get("visible") and element.get("stable_id") is not None
        }
    )
    return tuple(sample_visible_ids) == tuple(collected.get("visible_signature", ()))


def _resolve_scene_source_path(scene_cls):
    module = sys.modules.get(scene_cls.__module__)
    module_file = getattr(module, "__file__", None)
    if not module_file:
        return None
    return Path(module_file).resolve()


def _resolve_source_hint(scene_source_path, helper_source_path):
    scene_path = str(scene_source_path) if scene_source_path else None
    helper_path = str(helper_source_path) if helper_source_path else None

    for frame in reversed(traceback.extract_stack()):
        filename = _normalize_path(frame.filename)
        if helper_path and filename == helper_path:
            continue
        if scene_path and filename == scene_path:
            return {
                "method_name": frame.name,
                "line_number": frame.lineno,
                "code_line": (frame.line or "").strip(),
            }
    return None


def _normalize_path(path_value):
    if not path_value:
        return None
    try:
        return str(Path(path_value).resolve())
    except Exception:
        return str(path_value)


def _source_value(source_hint, key):
    if source_hint is None:
        return None
    return source_hint.get(key)


def _detect_play_type(args):
    if len(args) == 1 and args[0].__class__.__name__ == "Wait":
        return "wait"
    return "animation"


def _summarize_play_args(args, play_argument_hints):
    summary = []
    play_argument_hints = play_argument_hints or []
    for index, arg in enumerate(args):
        target = getattr(arg, "mobject", None)
        target_text = _display_text(target) if target is not None else None
        semantic_hint = play_argument_hints[index] if index < len(play_argument_hints) else {}
        summary.append(
            {
                "animation_type": arg.__class__.__name__,
                "target_class": target.__class__.__name__ if target is not None else None,
                "target_name": _mobject_name(target) if target is not None else None,
                "target_text": target_text,
                "source_target_name": semantic_hint.get("mobject") or semantic_hint.get("primary"),
            }
        )
    return summary


def _is_priority_animation_summary(animation_summary):
    for entry in animation_summary or []:
        if entry.get("animation_type") in _PRIORITY_ANIMATION_TYPES:
            return True
    return False


def _has_pre_removal_animation_summary(animation_summary):
    for entry in animation_summary or []:
        if entry.get("animation_type") in _PRE_REMOVAL_ANIMATION_TYPES:
            return True
    return False


def _pre_removal_descriptors(args, play_argument_hints=None):
    descriptors = []
    play_argument_hints = play_argument_hints or []
    for index, arg in enumerate(args):
        if arg is None or arg.__class__.__name__ not in _PRE_REMOVAL_ANIMATION_TYPES:
            continue
        semantic_hint = play_argument_hints[index] if index < len(play_argument_hints) else None
        descriptors.extend(
            _iter_removal_target_descriptors_from_arg(arg, semantic_hint=semantic_hint)
        )
    return descriptors


def _iter_target_descriptors(args, play_argument_hints=None):
    play_argument_hints = play_argument_hints or []
    for index, arg in enumerate(args):
        semantic_hint = play_argument_hints[index] if index < len(play_argument_hints) else None
        yield from _iter_mobject_descriptors_from_arg(arg, semantic_hint=semantic_hint)


def _iter_mobject_descriptors_from_arg(arg, semantic_hint=None):
    if arg is None:
        return

    if hasattr(arg, "animations"):
        nested_hints = []
        if isinstance(semantic_hint, dict):
            nested_hints = semantic_hint.get("nested", []) or []
        for index, nested in enumerate(getattr(arg, "animations", [])):
            nested_hint = nested_hints[index] if index < len(nested_hints) else None
            yield from _iter_mobject_descriptors_from_arg(nested, semantic_hint=nested_hint)

    semantic_hint = semantic_hint or {}
    for attr_name in ("mobject", "target_mobject", "starting_mobject"):
        mobject = getattr(arg, attr_name, None)
        if mobject is not None and _looks_like_mobject(mobject):
            yield {
                "mobject": mobject,
                "semantic_name": semantic_hint.get(attr_name) or semantic_hint.get("primary"),
            }

    if _looks_like_mobject(arg):
        yield {
            "mobject": arg,
            "semantic_name": semantic_hint.get("primary"),
        }


def _iter_removal_target_descriptors_from_arg(arg, semantic_hint=None):
    if arg is None:
        return

    if hasattr(arg, "animations"):
        nested_hints = []
        if isinstance(semantic_hint, dict):
            nested_hints = semantic_hint.get("nested", []) or []
        for index, nested in enumerate(getattr(arg, "animations", [])):
            nested_hint = nested_hints[index] if index < len(nested_hints) else None
            yield from _iter_removal_target_descriptors_from_arg(nested, semantic_hint=nested_hint)

    semantic_hint = semantic_hint or {}
    for attr_name in ("mobject", "starting_mobject"):
        mobject = getattr(arg, attr_name, None)
        if mobject is not None and _looks_like_mobject(mobject):
            yield {
                "mobject": mobject,
                "semantic_name": semantic_hint.get(attr_name) or semantic_hint.get("primary"),
            }

    if _looks_like_mobject(arg):
        yield {
            "mobject": arg,
            "semantic_name": semantic_hint.get("primary"),
        }


def _looks_like_mobject(value):
    return hasattr(value, "submobjects") and hasattr(value, "get_center")


def _should_track_object(mobject):
    if not _looks_like_mobject(mobject):
        return False
    class_name = mobject.__class__.__name__
    if class_name in _NON_VISUAL_MOBJECT_CLASSES:
        return False
    if class_name.endswith("Tracker"):
        return False
    return True


def _profile_key_for_duration(duration):
    if duration is None or duration <= 0:
        return "short"
    if duration >= _LONG_PLAY_SECONDS:
        return "long"
    if duration >= _MEDIUM_PLAY_SECONDS:
        return "medium"
    return "short"


def _sampling_profile_name(duration, priority_sampling):
    return (
        f"{_profile_key_for_duration(duration)}_priority"
        if priority_sampling
        else _profile_key_for_duration(duration)
    )


def _select_intermediate_roles(duration, priority_sampling):
    profile_key = _profile_key_for_duration(duration)
    if priority_sampling:
        profiles = {
            "short": [
                ("quarter", 0.25),
                ("mid", 0.5),
                ("three_quarter", 0.75),
            ],
            "medium": [
                ("one_sixth", 1.0 / 6.0),
                ("one_third", 1.0 / 3.0),
                ("mid", 0.5),
                ("two_thirds", 2.0 / 3.0),
                ("five_sixths", 5.0 / 6.0),
            ],
            "long": [
                ("one_eighth", 0.125),
                ("quarter", 0.25),
                ("three_eighths", 0.375),
                ("mid", 0.5),
                ("five_eighths", 0.625),
                ("three_quarter", 0.75),
                ("seven_eighths", 0.875),
            ],
        }
        return profiles.get(profile_key, profiles["short"])

    profiles = {
        "short": [("mid", 0.5)],
        "medium": [
            ("quarter", 0.25),
            ("mid", 0.5),
            ("three_quarter", 0.75),
        ],
        "long": [
            ("one_sixth", 1.0 / 6.0),
            ("one_third", 1.0 / 3.0),
            ("mid", 0.5),
            ("two_thirds", 2.0 / 3.0),
            ("five_sixths", 5.0 / 6.0),
        ],
    }
    return profiles.get(profile_key, profiles["short"])


def _should_capture_fraction(animation_time, duration, fraction):
    if duration is None or duration <= 0:
        return False

    threshold = duration * fraction
    frame_tolerance = max(1.0 / max(_safe_float(config["frame_rate"]) or 1.0, 1.0), 0.01)
    return animation_time + frame_tolerance >= threshold


def _serialize_play_record(play_record):
    return {
        key: value
        for key, value in play_record.items()
        if not str(key).startswith("_")
    }


def _compact_object_catalog_entry(entry):
    return {
        "stable_id": entry.get("stable_id"),
        "semantic_name": entry.get("semantic_name"),
        "semantic_name_source": entry.get("semantic_name_source"),
        "class_name": entry.get("class_name"),
        "semantic_class": entry.get("semantic_class"),
        "display_text": entry.get("display_text"),
        "created_in_method": entry.get("created_in_method"),
        "created_at_line": entry.get("created_at_line"),
        "created_from": entry.get("created_from"),
    }


def _frame_bounds_from_config():
    x_radius = _safe_float(config["frame_x_radius"]) or 0.0
    y_radius = _safe_float(config["frame_y_radius"]) or 0.0
    return {
        "min": [-x_radius, -y_radius, 0.0],
        "max": [x_radius, y_radius, 0.0],
        "width": _safe_float(config["frame_width"]) or 0.0,
        "height": _safe_float(config["frame_height"]) or 0.0,
    }


def _should_descend_layout_children(mobject, child_descriptors):
    if not child_descriptors:
        return False

    if _semantic_class(mobject) in _PRIMITIVE_LAYOUT_CLASSES:
        return False

    if mobject.__class__.__name__ in _COMPOSITE_GROUP_CLASSES:
        return True

    if len(child_descriptors) == 1:
        return True

    child_names = [child.__class__.__name__ for _, child in child_descriptors]
    if any(child_name in _COMPOSITE_GROUP_CLASSES for child_name in child_names):
        return True

    return False


def _derived_semantic_name(parent_entry, child_index):
    parent_name = parent_entry.get("semantic_name") or parent_entry.get("stable_id") or "mobject"
    return f"{parent_name}.{child_index}"


def _build_layout_assessment(elements, frame_bounds):
    offscreen_elements = []
    overlap_issues = []

    for element in elements:
        overflow = _frame_overflow(element.get("bounds"), frame_bounds)
        if overflow is not None:
            offscreen_elements.append(
                {
                    "stable_id": element.get("stable_id"),
                    "semantic_name": element.get("semantic_name"),
                    "class_name": element.get("class_name"),
                    "overflow": overflow,
                }
            )

    for index, left in enumerate(elements):
        for right in elements[index + 1 :]:
            if not _should_report_layout_overlap(left, right):
                continue
            overlap = _bbox_overlap_metrics(left.get("bounds"), right.get("bounds"))
            if overlap is None:
                continue
            if overlap["area"] < _LAYOUT_MIN_OVERLAP_AREA:
                continue
            if overlap["min_area_ratio"] < _LAYOUT_MIN_OVERLAP_RATIO:
                continue
            overlap_issues.append(
                {
                    "left": _layout_object_ref(left),
                    "right": _layout_object_ref(right),
                    "intersection_area": overlap["area"],
                    "intersection_bounds": overlap["bounds"],
                    "min_area_ratio": overlap["min_area_ratio"],
                }
            )

    return {
        "is_layout_normal": len(offscreen_elements) == 0 and len(overlap_issues) == 0,
        "issue_count": len(offscreen_elements) + len(overlap_issues),
        "offscreen_count": len(offscreen_elements),
        "overlap_issue_count": len(overlap_issues),
        "offscreen_elements": offscreen_elements,
        "overlap_issues": overlap_issues,
    }


def _frame_overflow(bounds, frame_bounds):
    if bounds is None or frame_bounds is None:
        return None

    min_corner = bounds.get("min")
    max_corner = bounds.get("max")
    frame_min = frame_bounds.get("min")
    frame_max = frame_bounds.get("max")
    if min_corner is None or max_corner is None or frame_min is None or frame_max is None:
        return None

    overflow = {
        "left": max(_safe_float(frame_min[0] - min_corner[0]) or 0.0, 0.0),
        "right": max(_safe_float(max_corner[0] - frame_max[0]) or 0.0, 0.0),
        "bottom": max(_safe_float(frame_min[1] - min_corner[1]) or 0.0, 0.0),
        "top": max(_safe_float(max_corner[1] - frame_max[1]) or 0.0, 0.0),
    }
    if max(overflow.values()) <= _LAYOUT_OFFSCREEN_TOLERANCE:
        return None
    return overflow


def _should_report_layout_overlap(left, right):
    left_class = left.get("semantic_class")
    right_class = right.get("semantic_class")
    if left_class not in _TEXTUAL_CLASSES and right_class not in _TEXTUAL_CLASSES:
        return False
    return True


def _bbox_overlap_metrics(left_bounds, right_bounds):
    if left_bounds is None or right_bounds is None:
        return None

    left_min = left_bounds.get("min")
    left_max = left_bounds.get("max")
    right_min = right_bounds.get("min")
    right_max = right_bounds.get("max")
    if left_min is None or left_max is None or right_min is None or right_max is None:
        return None

    overlap_min_x = max(left_min[0], right_min[0])
    overlap_max_x = min(left_max[0], right_max[0])
    overlap_min_y = max(left_min[1], right_min[1])
    overlap_max_y = min(left_max[1], right_max[1])
    overlap_width = overlap_max_x - overlap_min_x
    overlap_height = overlap_max_y - overlap_min_y
    if overlap_width <= _EPSILON or overlap_height <= _EPSILON:
        return None

    area = overlap_width * overlap_height
    left_area = max((left_max[0] - left_min[0]) * (left_max[1] - left_min[1]), _EPSILON)
    right_area = max((right_max[0] - right_min[0]) * (right_max[1] - right_min[1]), _EPSILON)
    return {
        "area": _safe_float(area) or 0.0,
        "min_area_ratio": _safe_float(area / min(left_area, right_area)) or 0.0,
        "bounds": {
            "min": _safe_point([overlap_min_x, overlap_min_y, 0.0]),
            "max": _safe_point([overlap_max_x, overlap_max_y, 0.0]),
        },
    }


def _layout_object_ref(element):
    return {
        "stable_id": element.get("stable_id"),
        "semantic_name": element.get("semantic_name"),
        "class_name": element.get("class_name"),
        "element_path": element.get("element_path"),
        "top_level_semantic_name": element.get("top_level_semantic_name"),
    }


def _build_report_summary(samples, object_catalog, play_records, observed_render_frame_count):
    root_counts = [sample["root_object_count"] for sample in samples]
    textual_counts = [sample["textual_object_count"] for sample in samples]
    play_type_counter = Counter(play["play_type"] for play in play_records)
    sample_role_counter = Counter(sample["sample_role"] for sample in samples)
    trigger_counter = Counter(sample["trigger"] for sample in samples)
    object_class_counter = Counter(entry["class_name"] for entry in object_catalog)
    visibility_change_sample_count = sum(1 for sample in samples if "visibility_change" in sample)
    priority_play_count = sum(1 for play in play_records if play.get("priority_sampling"))

    return {
        "observed_render_frame_count": observed_render_frame_count,
        "sample_count": len(samples),
        "sample_reduction_ratio": _safe_float(
            (len(samples) / observed_render_frame_count) if observed_render_frame_count else 1.0
        ),
        "play_count": len(play_records),
        "priority_play_count": priority_play_count,
        "visibility_change_sample_count": visibility_change_sample_count,
        "object_catalog_count": len(object_catalog),
        "max_root_objects_in_sample": max(root_counts) if root_counts else 0,
        "max_textual_objects_in_sample": max(textual_counts) if textual_counts else 0,
        "top_object_classes": [
            {"class_name": class_name, "count": count}
            for class_name, count in object_class_counter.most_common(10)
        ],
        "play_type_distribution": dict(play_type_counter),
        "sample_role_distribution": dict(sample_role_counter),
        "trigger_distribution": dict(trigger_counter),
    }


def _unique_roots(scene):
    roots = []
    seen = set()
    for collection_name in ("mobjects", "foreground_mobjects"):
        for mobject in getattr(scene, collection_name, []) or []:
            object_id = id(mobject)
            if object_id in seen:
                continue
            seen.add(object_id)
            roots.append(mobject)
    return roots


def _build_semantic_mobject_record(
    mobject,
    registry_entry,
    foreground_ids,
    camera=None,
    require_visible=True,
):
    points = _safe_points(mobject)
    width = _safe_float(getattr(mobject, "width", None))
    height = _safe_float(getattr(mobject, "height", None))
    depth = _safe_float(getattr(mobject, "depth", None))
    opacity_values = _opacity_values(mobject)
    max_opacity = max(opacity_values) if opacity_values else None
    stroke_width = _safe_float(_call_with_optional_bool(mobject, "get_stroke_width"))
    world_bounds = _compute_bounds(
        points=points,
        center=_safe_point(_safe_call(mobject, "get_center")),
        width=width,
        height=height,
        depth=depth,
    )

    if world_bounds is None:
        return None
    screen_bounds = _compute_display_bounds(
        camera=camera,
        mobject=mobject,
        points=points,
        world_bounds=world_bounds,
    )
    if screen_bounds is None:
        screen_bounds = world_bounds

    visible = _is_visible(int(points.shape[0]), world_bounds, max_opacity)
    if require_visible and not visible:
        return None

    return {
        "stable_id": registry_entry["stable_id"],
        "name": registry_entry["name"],
        "semantic_name": registry_entry.get("semantic_name"),
        "semantic_name_source": registry_entry.get("semantic_name_source"),
        "class_name": registry_entry["class_name"],
        "semantic_class": registry_entry["semantic_class"],
        "display_text": registry_entry["display_text"],
        "visible": visible,
        "is_foreground": id(mobject) in foreground_ids,
        "submobject_count": len(getattr(mobject, "submobjects", []) or []),
        "z_index": _safe_float(getattr(mobject, "z_index", 0.0)),
        "center": screen_bounds["center"],
        "screen_center": screen_bounds["center"],
        "world_center": world_bounds["center"],
        "bounds": {
            "min": screen_bounds["min"],
            "max": screen_bounds["max"],
        },
        "screen_bounds": {
            "min": screen_bounds["min"],
            "max": screen_bounds["max"],
        },
        "world_bounds": {
            "min": world_bounds["min"],
            "max": world_bounds["max"],
        },
        "size": {
            "width": screen_bounds["width"],
            "height": screen_bounds["height"],
            "depth": screen_bounds["depth"],
        },
        "screen_size": {
            "width": screen_bounds["width"],
            "height": screen_bounds["height"],
            "depth": screen_bounds["depth"],
        },
        "world_size": {
            "width": world_bounds["width"],
            "height": world_bounds["height"],
            "depth": world_bounds["depth"],
        },
        "occupancy": {
            "area_2d": _safe_float(screen_bounds["width"] * screen_bounds["height"]),
            "volume_3d": _safe_float(world_bounds["width"] * world_bounds["height"] * world_bounds["depth"]),
        },
        "points_count": int(points.shape[0]),
        "opacity": max_opacity,
        "stroke_width": stroke_width,
        "shape_hints": _shape_hints(mobject),
    }


def _shape_hints(mobject):
    hints = {}

    start = _safe_point(getattr(mobject, "start", None))
    end = _safe_point(getattr(mobject, "end", None))
    if start is not None and end is not None:
        hints["start"] = start
        hints["end"] = end

    radius = _safe_float(getattr(mobject, "radius", None))
    if radius is not None:
        hints["radius"] = radius

    angle = _safe_float(getattr(mobject, "angle", None))
    if angle is not None:
        hints["angle"] = angle

    start_angle = _safe_float(getattr(mobject, "start_angle", None))
    if start_angle is not None:
        hints["start_angle"] = start_angle

    arc_center = _safe_point(getattr(mobject, "arc_center", None))
    if arc_center is not None:
        hints["arc_center"] = arc_center

    return hints


def _semantic_class(mobject):
    class_name = mobject.__class__.__name__
    if class_name == "Text":
        return "text"
    if class_name.startswith("MathTex") or class_name == "Tex":
        return "formula"
    if class_name == "Dot":
        return "point"
    if class_name in {"Line", "DashedLine", "Arrow", "DoubleArrow"}:
        return "line"
    if class_name in {"Circle", "Arc", "Square", "Rectangle", "Polygon"}:
        return "shape"
    if class_name.endswith("Group"):
        return "group"
    return "other"


def _display_text(mobject):
    if mobject is None:
        return None

    text = getattr(mobject, "text", None)
    if isinstance(text, str) and text.strip():
        return text.strip()

    original_text = getattr(mobject, "original_text", None)
    if isinstance(original_text, str) and original_text.strip():
        return original_text.strip()

    tex_string = getattr(mobject, "tex_string", None)
    if isinstance(tex_string, str) and tex_string.strip():
        return tex_string.strip()

    tex_strings = getattr(mobject, "tex_strings", None)
    if isinstance(tex_strings, (list, tuple)):
        joined = " ".join(str(item).strip() for item in tex_strings if str(item).strip())
        return joined or None

    return None


def _mobject_name(mobject):
    if mobject is None:
        return None
    name = getattr(mobject, "name", None)
    if isinstance(name, str) and name.strip():
        return name
    return mobject.__class__.__name__


def _resolve_semantic_name(mobject, semantic_hint=None, semantic_hint_source=None):
    semantic_hint = _normalize_semantic_name(semantic_hint)
    if semantic_hint is not None:
        return semantic_hint, semantic_hint_source or "source_argument"

    raw_name = getattr(mobject, "name", None)
    if isinstance(raw_name, str):
        raw_name = raw_name.strip()
        if raw_name and raw_name != mobject.__class__.__name__:
            return raw_name, "mobject_name"

    display_text = _display_text(mobject)
    if display_text:
        return display_text, "display_text"

    return mobject.__class__.__name__, "class_name"


def _prefer_semantic_name(current_name, current_source, candidate_name, candidate_source):
    candidate_name = _normalize_semantic_name(candidate_name)
    candidate_source = candidate_source or "class_name"
    current_source = current_source or "class_name"

    if candidate_name is None:
        return current_name, current_source
    if current_name is None:
        return candidate_name, candidate_source

    current_rank = _SEMANTIC_NAME_SOURCE_RANK.get(current_source, 0)
    candidate_rank = _SEMANTIC_NAME_SOURCE_RANK.get(candidate_source, 0)
    if candidate_rank > current_rank:
        return candidate_name, candidate_source
    return current_name, current_source


def _extract_positional_semantic_hints(source_hint, scene_source_path, method_names, arg_count):
    call_node = _find_enclosing_self_call(
        scene_source_path=scene_source_path,
        line_number=_source_value(source_hint, "line_number"),
        method_names=method_names,
    )
    if call_node is None:
        return [None] * arg_count

    hints = []
    for argument in call_node.args[:arg_count]:
        hints.append(_semantic_name_from_simple_expr(argument))

    while len(hints) < arg_count:
        hints.append(None)
    return hints


def _extract_play_argument_hints(source_hint, scene_source_path, arg_count):
    call_node = _find_enclosing_self_call(
        scene_source_path=scene_source_path,
        line_number=_source_value(source_hint, "line_number"),
        method_names={"play"},
    )
    if call_node is None:
        return [{} for _ in range(arg_count)]

    hints = []
    for argument in call_node.args[:arg_count]:
        hints.append(_semantic_hints_for_play_expr(argument))

    while len(hints) < arg_count:
        hints.append({})
    return hints


def _find_enclosing_self_call(scene_source_path, line_number, method_names):
    if scene_source_path is None or line_number is None:
        return None

    source_tree = _load_source_tree(scene_source_path)
    if source_tree is None:
        return None

    candidates = []
    for node in ast.walk(source_tree):
        if not isinstance(node, ast.Call):
            continue
        if not _is_self_method_call(node, method_names):
            continue
        node_lineno = getattr(node, "lineno", None)
        node_end_lineno = getattr(node, "end_lineno", None)
        if node_lineno is None or node_end_lineno is None:
            continue
        if node_lineno <= line_number <= node_end_lineno:
            candidates.append(node)

    if not candidates:
        return None

    candidates.sort(
        key=lambda node: (
            getattr(node, "end_lineno", 0) - getattr(node, "lineno", 0),
            getattr(node, "lineno", 0),
        )
    )
    return candidates[0]


def _load_source_tree(scene_source_path):
    normalized_path = _normalize_path(scene_source_path)
    if normalized_path is None:
        return None

    cached = _SOURCE_AST_CACHE.get(normalized_path)
    if cached is not None or normalized_path in _SOURCE_AST_CACHE:
        return cached

    try:
        source_text = Path(normalized_path).read_text(encoding="utf-8-sig")
        source_tree = ast.parse(source_text)
    except Exception:
        _SOURCE_AST_CACHE[normalized_path] = None
        return None

    _SOURCE_AST_CACHE[normalized_path] = source_tree
    return source_tree


def _is_self_method_call(call_node, method_names):
    func = getattr(call_node, "func", None)
    return (
        isinstance(func, ast.Attribute)
        and func.attr in (method_names or set())
        and isinstance(func.value, ast.Name)
        and func.value.id == "self"
    )


def _semantic_hints_for_play_expr(expr):
    if isinstance(expr, ast.Call):
        animate_target = _extract_animate_target_expr(expr)
        if animate_target is not None:
            primary = _semantic_name_from_simple_expr(animate_target)
            return {
                "primary": primary,
                "mobject": primary,
            }

        func_name = _call_name(expr.func)
        if func_name in {"AnimationGroup", "LaggedStart", "Succession"}:
            nested = [_semantic_hints_for_play_expr(argument) for argument in expr.args]
            primary = next(
                (
                    nested_hint.get("primary")
                    for nested_hint in nested
                    if nested_hint.get("primary") is not None
                ),
                None,
            )
            return {
                "primary": primary,
                "nested": nested,
            }

        if func_name in {
            "Transform",
            "ReplacementTransform",
            "TransformFromCopy",
            "FadeTransform",
            "FadeTransformPieces",
            "TransformMatchingShapes",
            "TransformMatchingTex",
        }:
            primary = _semantic_name_from_simple_expr(expr.args[0]) if len(expr.args) > 0 else None
            target = _semantic_name_from_simple_expr(expr.args[1]) if len(expr.args) > 1 else None
            return {
                "primary": primary,
                "mobject": primary,
                "target_mobject": target,
            }

        primary = _semantic_name_from_simple_expr(expr.args[0]) if expr.args else None
        return {
            "primary": primary,
            "mobject": primary,
        }

    primary = _semantic_name_from_simple_expr(expr)
    return {"primary": primary}


def _extract_animate_target_expr(expr):
    if isinstance(expr, ast.Call):
        return _extract_animate_target_expr(expr.func)
    if isinstance(expr, ast.Attribute):
        if isinstance(expr.value, ast.Attribute) and expr.value.attr == "animate":
            return expr.value.value
        return _extract_animate_target_expr(expr.value)
    return None


def _call_name(func):
    if isinstance(func, ast.Name):
        return func.id
    if isinstance(func, ast.Attribute):
        return func.attr
    return None


def _semantic_name_from_simple_expr(expr):
    if isinstance(expr, (ast.Name, ast.Attribute, ast.Subscript)):
        try:
            return _normalize_semantic_name(ast.unparse(expr))
        except Exception:
            return None
    return None


def _normalize_semantic_name(name):
    if not isinstance(name, str):
        return None
    name = name.strip()
    return name or None


def _object_refs_from_ids(object_registry, object_ids):
    refs = []
    for stable_id in object_ids:
        entry = object_registry.get(stable_id)
        if entry is None:
            continue
        refs.append(
            {
                "stable_id": stable_id,
                "semantic_name": entry.get("semantic_name"),
                "class_name": entry.get("class_name"),
                "display_text": entry.get("display_text"),
            }
        )
    return refs


def _safe_points(mobject):
    raw_points = _safe_call(mobject, "get_all_points")
    if raw_points is None:
        return np.empty((0, 3))

    points = np.array(raw_points, dtype=float)
    if points.size == 0:
        return np.empty((0, 3))
    if points.ndim == 1:
        points = points.reshape(1, -1)
    if points.shape[1] == 1:
        points = np.pad(points, ((0, 0), (0, 2)))
    elif points.shape[1] == 2:
        points = np.pad(points, ((0, 0), (0, 1)))
    elif points.shape[1] > 3:
        points = points[:, :3]
    return points


def _compute_bounds(points, center, width, height, depth):
    if points.size > 0:
        min_corner = points.min(axis=0)
        max_corner = points.max(axis=0)
        measured_center = ((min_corner + max_corner) / 2.0).tolist()
        return {
            "min": _safe_point(min_corner),
            "max": _safe_point(max_corner),
            "center": _safe_point(center if center is not None else measured_center),
            "width": _safe_float(width if width is not None else max_corner[0] - min_corner[0]) or 0.0,
            "height": _safe_float(height if height is not None else max_corner[1] - min_corner[1]) or 0.0,
            "depth": _safe_float(depth if depth is not None else max_corner[2] - min_corner[2]) or 0.0,
        }

    if center is None:
        return None

    width = width or 0.0
    height = height or 0.0
    depth = depth or 0.0
    half_size = np.array([width / 2.0, height / 2.0, depth / 2.0], dtype=float)
    center_vector = np.array(center, dtype=float)
    min_corner = center_vector - half_size
    max_corner = center_vector + half_size
    return {
        "min": _safe_point(min_corner),
        "max": _safe_point(max_corner),
        "center": _safe_point(center_vector),
        "width": _safe_float(width) or 0.0,
        "height": _safe_float(height) or 0.0,
        "depth": _safe_float(depth) or 0.0,
    }


def _compute_display_bounds(camera, mobject, points, world_bounds):
    display_points = _display_points(camera, mobject, points, world_bounds)
    if display_points.size <= 0:
        return None
    return _compute_bounds(
        points=display_points,
        center=None,
        width=None,
        height=None,
        depth=None,
    )


def _display_points(camera, mobject, points, world_bounds):
    base_points = points
    if base_points.size <= 0:
        base_points = _bounds_corner_points(world_bounds)
    if base_points.size <= 0:
        return base_points

    transform = getattr(camera, "transform_points_pre_display", None)
    if not callable(transform):
        return base_points

    try:
        transformed = transform(mobject, np.array(base_points, dtype=float))
    except Exception:
        return base_points
    return _safe_points(transformed)


def _bounds_corner_points(bounds):
    if bounds is None:
        return np.zeros((0, 3))

    min_corner = bounds.get("min")
    max_corner = bounds.get("max")
    if min_corner is None or max_corner is None:
        return np.zeros((0, 3))

    min_corner = np.array(min_corner, dtype=float)
    max_corner = np.array(max_corner, dtype=float)
    xs = [min_corner[0], max_corner[0]]
    ys = [min_corner[1], max_corner[1]]
    zs = [min_corner[2], max_corner[2]]
    corners = [[x, y, z] for x in xs for y in ys for z in zs]
    return np.array(corners, dtype=float)


def _is_visible(points_count, bounds, max_opacity):
    if max_opacity is not None and max_opacity <= _EPSILON:
        return False
    return (
        points_count > 0
        or bounds["width"] > _EPSILON
        or bounds["height"] > _EPSILON
        or bounds["depth"] > _EPSILON
        or (max_opacity is not None and max_opacity > _EPSILON)
    )


def _opacity_values(mobject):
    values = []
    for method_name in ("get_fill_opacity", "get_stroke_opacity", "get_opacity"):
        value = _call_with_optional_bool(mobject, method_name)
        if value is None:
            continue
        safe_value = _safe_float(value)
        if safe_value is not None:
            values.append(safe_value)
    return values


def _call_with_optional_bool(mobject, method_name):
    method = getattr(mobject, method_name, None)
    if not callable(method):
        return None
    try:
        return method()
    except TypeError:
        try:
            return method(False)
        except Exception:
            return None
    except Exception:
        return None


def _safe_call(mobject, method_name):
    method = getattr(mobject, method_name, None)
    if not callable(method):
        return None
    try:
        return method()
    except Exception:
        return None


def _safe_point(vector):
    if vector is None:
        return None

    array = np.array(vector, dtype=float).reshape(-1)
    if array.size == 0:
        return [0.0, 0.0, 0.0]
    if array.size == 1:
        array = np.array([array[0], 0.0, 0.0], dtype=float)
    elif array.size == 2:
        array = np.array([array[0], array[1], 0.0], dtype=float)
    elif array.size > 3:
        array = array[:3]
    return [_safe_float(value) or 0.0 for value in array]


def _safe_float(value):
    if value is None:
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if not np.isfinite(number):
        return None
    return round(number, _ROUND_DIGITS)
