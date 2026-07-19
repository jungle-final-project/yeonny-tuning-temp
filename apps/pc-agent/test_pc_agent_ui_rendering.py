from __future__ import annotations

import gc
import inspect
import threading
import unittest
import weakref

from pc_agent_ui_rendering import (
    AMBER,
    ORANGE,
    SLATE,
    AnimationCallbackController,
    DeferredFluidWaveCache,
    FLUID_WAVE_FRAME_COUNT,
    FLUID_WAVE_SIZE,
    FluidWaveDisplayState,
    RetainedAssetCache,
    WindowVisibilityState,
    build_fluid_wave_cache,
    fluid_wave_amplitude,
    fluid_wave_color,
    home_hardware_icon_cache_key,
    quantize_usage,
    render_finding_icon,
    render_fluid_wave_frame,
    render_hardware_icon,
    render_home_hardware_icon,
    render_number_badge,
    render_progress_ring,
    render_result_icon,
    render_rounded_surface,
    render_status_icon,
    render_step_node,
)


class _Reference:
    pass


class _FakeAfterScheduler:
    def __init__(self) -> None:
        self.next_id = 1
        self.callbacks: dict[int, object] = {}
        self.cancelled: list[int] = []

    def schedule(self, delay_ms: int, callback: object) -> int:
        del delay_ms
        callback_id = self.next_id
        self.next_id += 1
        self.callbacks[callback_id] = callback
        return callback_id

    def cancel(self, callback_id: int) -> None:
        self.cancelled.append(callback_id)
        self.callbacks.pop(callback_id, None)

    def run_next(self) -> None:
        callback_id = min(self.callbacks)
        callback = self.callbacks.pop(callback_id)
        callback()

    def run_all(self, limit: int = 1000) -> None:
        iterations = 0
        while self.callbacks:
            self.run_next()
            iterations += 1
            if iterations > limit:
                raise AssertionError("scheduled callbacks did not finish")


class PcAgentUiRenderingTest(unittest.TestCase):
    def test_window_visibility_distinguishes_explicit_hide_from_transient_map_events(self) -> None:
        state = WindowVisibilityState()
        root = object()

        self.assertTrue(state.ui_active("normal", True, True))
        self.assertTrue(state.is_root_event(root, root))
        self.assertFalse(state.is_root_event(object(), root))

        state.hide()
        self.assertFalse(state.ui_active("normal", True, True))
        state.show()
        self.assertFalse(state.ui_active("iconic", False, False))
        self.assertTrue(state.ui_active("normal", True, True))

    def test_usage_quantization_clamps_without_replacing_the_real_value(self) -> None:
        actual_usage = 47.25

        self.assertEqual(50, quantize_usage(actual_usage))
        self.assertEqual(79, quantize_usage(79))
        self.assertEqual(80, quantize_usage(80))
        self.assertEqual(89, quantize_usage(89))
        self.assertEqual(90, quantize_usage(90))
        self.assertEqual(0, quantize_usage(-20))
        self.assertEqual(100, quantize_usage(140))
        self.assertEqual(47.25, actual_usage)

    def test_wave_amplitude_and_color_follow_confirmed_load_ranges(self) -> None:
        self.assertEqual(0.0, fluid_wave_amplitude(None))
        self.assertAlmostEqual(0.65, fluid_wave_amplitude(0), places=3)
        self.assertAlmostEqual(2.5, fluid_wave_amplitude(20), places=3)
        self.assertAlmostEqual(7.0, fluid_wave_amplitude(50), places=3)
        self.assertAlmostEqual(14.0, fluid_wave_amplitude(75), places=3)
        self.assertAlmostEqual(22.0, fluid_wave_amplitude(92), places=3)
        self.assertEqual(SLATE, fluid_wave_color(79))
        self.assertEqual(AMBER, fluid_wave_color(80))
        self.assertEqual(AMBER, fluid_wave_color(89))
        self.assertEqual(ORANGE, fluid_wave_color(90))

    def test_wave_display_state_keeps_measurement_and_animation_separate(self) -> None:
        state = FluidWaveDisplayState()
        state.set_measurement(20.0)
        self.assertEqual(20.0, state.target_usage)
        self.assertEqual(20.0, state.display_usage)

        state.set_measurement(92.0)
        first_display = state.advance()
        self.assertEqual(92.0, state.target_usage)
        self.assertGreater(first_display, 20.0)
        self.assertLess(first_display, 92.0)
        self.assertEqual(30, state.bucket)

    def test_fluid_frames_are_pre_generated_and_visually_change(self) -> None:
        first = render_fluid_wave_frame(75, 0)
        second = render_fluid_wave_frame(75, FLUID_WAVE_FRAME_COUNT // 4)

        self.assertEqual(FLUID_WAVE_SIZE, first.size)
        self.assertNotEqual(first.tobytes(), second.tobytes())
        generated = build_fluid_wave_cache(lambda image: image, usage_buckets=(20, 92))
        self.assertEqual({20, 92}, set(generated))
        self.assertEqual(FLUID_WAVE_FRAME_COUNT, len(generated[20]))

    def test_zero_activity_has_subtle_motion_without_inventing_a_measurement(self) -> None:
        first = render_fluid_wave_frame(0, 0)
        second = render_fluid_wave_frame(0, FLUID_WAVE_FRAME_COUNT // 4)

        self.assertLess(fluid_wave_amplitude(0), 1.0)
        self.assertNotEqual(first.tobytes(), second.tobytes())
        state = FluidWaveDisplayState()
        state.set_measurement(0)
        self.assertEqual(0.0, state.target_usage)

    def test_pillow_shapes_have_antialiased_edges_at_actual_size(self) -> None:
        surface = render_rounded_surface(206, 180, 12, "#ffffff", "#d7dce0", 1)
        ring = render_progress_ring(4)
        surface_alpha = set(surface.getchannel("A").getdata())
        ring_alpha = set(ring.getchannel("A").getdata())

        self.assertTrue(any(0 < alpha < 255 for alpha in surface_alpha))
        self.assertTrue(any(0 < alpha < 255 for alpha in ring_alpha))

    def test_progress_ring_uses_smooth_unclipped_supersampled_edges(self) -> None:
        ring = render_progress_ring(15)
        alpha = ring.getchannel("A")
        bounds = alpha.getbbox()
        edge_alpha = max(
            max(alpha.crop((0, 0, ring.width, 1)).getdata()),
            max(alpha.crop((0, ring.height - 1, ring.width, ring.height)).getdata()),
            max(alpha.crop((0, 0, 1, ring.height)).getdata()),
            max(alpha.crop((ring.width - 1, 0, ring.width, ring.height)).getdata()),
        )

        self.assertIsNotNone(bounds)
        self.assertLessEqual(edge_alpha, 8)
        self.assertGreater(len({value for value in alpha.getdata() if 0 < value < 255}), 8)

    def test_progress_ring_has_no_explicit_caps_and_uses_full_ellipse_at_100(self) -> None:
        source = inspect.getsource(render_progress_ring)
        zero = render_progress_ring(0)
        complete = render_progress_ring(100)

        self.assertNotIn("cap_radius", source)
        self.assertNotIn("for angle in", source)
        self.assertIn("if value == 100:", source)
        self.assertIn('draw.ellipse(bounds, outline=_rgba("#111111")', source)
        self.assertNotEqual(zero.tobytes(), complete.tobytes())

    def test_number_badge_is_antialiased_at_target_size(self) -> None:
        badge = render_number_badge(26)
        alpha = badge.getchannel("A")

        self.assertEqual((26, 26), badge.size)
        self.assertIsNotNone(badge.getbbox())
        self.assertTrue(any(0 < value < 255 for value in alpha.getdata()))

    def test_hardware_step_status_and_progress_assets_render_at_target_size(self) -> None:
        for component in ("cpu", "gpu", "ram", "disk"):
            icon = render_hardware_icon(component, 30)
            self.assertEqual((30, 30), icon.size)
            self.assertIsNotNone(icon.getbbox())
        self.assertEqual((34, 34), render_step_node("done-check", 34).size)
        self.assertNotEqual(
            render_status_icon("running", 16, frame_index=0).tobytes(),
            render_status_icon("running", 16, frame_index=6).tobytes(),
        )
        self.assertNotEqual(render_progress_ring(4).tobytes(), render_progress_ring(50).tobytes())
        self.assertNotEqual(render_progress_ring(50).tobytes(), render_progress_ring(92).tobytes())

    def test_result_page_icons_are_supersampled_at_their_display_sizes(self) -> None:
        header = render_result_icon(42)
        warning = render_finding_icon("warn", 24)

        self.assertEqual((42, 42), header.size)
        self.assertEqual((24, 24), warning.size)
        self.assertIsNotNone(header.getbbox())
        self.assertIsNotNone(warning.getbbox())
        self.assertTrue(any(0 < alpha < 255 for alpha in header.getchannel("A").getdata()))
        self.assertTrue(any(0 < alpha < 255 for alpha in warning.getchannel("A").getdata()))

    def test_home_hardware_icons_share_centered_monoline_bounds(self) -> None:
        icons = {
            component: render_home_hardware_icon(component, 30, "#666666")
            for component in ("cpu", "gpu", "ram", "disk")
        }

        self.assertEqual(4, len({icon.tobytes() for icon in icons.values()}))
        for icon in icons.values():
            self.assertEqual((30, 30), icon.size)
            visible_alpha = icon.getchannel("A").point(lambda alpha: 255 if alpha >= 16 else 0)
            bounds = visible_alpha.getbbox()
            self.assertIsNotNone(bounds)
            left, top, right, bottom = bounds or (0, 0, 0, 0)
            self.assertLessEqual(abs((left + right) / 2 - 15), 1.5)
            self.assertLessEqual(abs((top + bottom) / 2 - 15), 1.5)
            self.assertGreaterEqual(right - left, 19)
            self.assertLessEqual(right - left, 23)
            self.assertGreaterEqual(bottom - top, 16)
            self.assertLessEqual(bottom - top, 23)

    def test_home_hardware_icon_cache_reuses_component_and_color_variants(self) -> None:
        cache = RetainedAssetCache()
        neutral_key = home_hardware_icon_cache_key("disk", "#666666", 30)
        warning_key = home_hardware_icon_cache_key("disk", "#e58b00", 30)
        generated = 0

        def create_reference() -> _Reference:
            nonlocal generated
            generated += 1
            return _Reference()

        neutral = cache.get(neutral_key, create_reference)
        self.assertIs(neutral, cache.get(neutral_key, create_reference))
        self.assertIsNot(neutral, cache.get(warning_key, create_reference))
        self.assertEqual(2, generated)

        for component in ("cpu", "gpu", "ram", "disk"):
            neutral_icon = render_home_hardware_icon(component, 30, "#666666")
            warning_icon = render_home_hardware_icon(component, 30, "#e58b00")
            self.assertEqual((30, 30), neutral_icon.size)
            self.assertEqual((30, 30), warning_icon.size)
            self.assertNotEqual(neutral_icon.tobytes(), warning_icon.tobytes())

    def test_static_and_progress_assets_are_reused_until_the_key_changes(self) -> None:
        cache = RetainedAssetCache()
        generation_count = 0

        def create_reference() -> _Reference:
            nonlocal generation_count
            generation_count += 1
            return _Reference()

        first = cache.get(("ring", 4), create_reference)
        rerendered = cache.get(("ring", 4), create_reference)
        changed = cache.get(("ring", 50), create_reference)

        self.assertIs(first, rerendered)
        self.assertIsNot(first, changed)
        self.assertEqual(2, generation_count)

    def test_cache_retains_photo_reference_across_page_rerender_and_clears_on_close(self) -> None:
        cache = RetainedAssetCache()
        photo = _Reference()
        reference = weakref.ref(photo)
        rendered = cache.get(("hardware", "cpu"), lambda: photo)
        del photo
        gc.collect()

        self.assertIs(rendered, cache.get(("hardware", "cpu"), _Reference))
        self.assertIsNotNone(reference())
        del rendered
        cache.clear()
        gc.collect()
        self.assertIsNone(reference())

    def test_animation_callback_pauses_resumes_without_duplicates_and_cancels_on_close(self) -> None:
        scheduler = _FakeAfterScheduler()
        frames = 0

        def on_frame() -> None:
            nonlocal frames
            frames += 1

        controller = AnimationCallbackController(scheduler.schedule, scheduler.cancel, on_frame, 33)
        controller.resume()
        controller.resume()
        self.assertEqual(1, len(scheduler.callbacks))

        scheduler.run_next()
        self.assertEqual(1, frames)
        self.assertEqual(1, len(scheduler.callbacks))

        controller.pause()
        self.assertEqual(0, len(scheduler.callbacks))
        controller.resume()
        controller.resume()
        self.assertEqual(1, len(scheduler.callbacks))

        pending_id = controller.after_id
        controller.close()
        self.assertIn(pending_id, scheduler.cancelled)
        self.assertEqual(0, len(scheduler.callbacks))
        controller.resume()
        self.assertEqual(0, len(scheduler.callbacks))

    def test_deferred_wave_cache_does_not_render_before_scheduled_callback(self) -> None:
        scheduler = _FakeAfterScheduler()
        rendered: list[tuple[int, int]] = []
        converted: list[tuple[int, int]] = []
        cache = DeferredFluidWaveCache(
            scheduler.schedule,
            scheduler.cancel,
            lambda bucket, frame: rendered.append((bucket, frame)) or (bucket, frame),
            lambda image: converted.append(image) or image,
            frame_count=4,
            batch_size=2,
        )

        self.assertEqual((), cache.cached_buckets)
        self.assertTrue(cache.request(20))
        self.assertEqual([], rendered)
        self.assertEqual([], converted)
        self.assertEqual(1, len(scheduler.callbacks))

        scheduler.run_all()
        self.assertEqual([(20, 0), (20, 1), (20, 2), (20, 3)], rendered)
        self.assertEqual((20,), cache.cached_buckets)

    def test_deferred_wave_cache_builds_only_requested_bucket_and_deduplicates_requests(self) -> None:
        scheduler = _FakeAfterScheduler()
        rendered: list[tuple[int, int]] = []
        cache = DeferredFluidWaveCache(
            scheduler.schedule,
            scheduler.cancel,
            lambda bucket, frame: rendered.append((bucket, frame)) or (bucket, frame),
            lambda image: image,
            frame_count=FLUID_WAVE_FRAME_COUNT,
            batch_size=3,
        )

        self.assertTrue(cache.request(70))
        self.assertFalse(cache.request(70))
        self.assertEqual((70,), cache.in_progress_buckets)
        scheduler.run_all()
        first_frames = cache.get(70)

        self.assertEqual(FLUID_WAVE_FRAME_COUNT, len(rendered))
        self.assertEqual({70}, {bucket for bucket, _ in rendered})
        self.assertFalse(cache.request(70))
        self.assertIs(first_frames, cache.get(70))
        self.assertEqual(0, len(scheduler.callbacks))

    def test_deferred_wave_cache_uses_bounded_lru(self) -> None:
        scheduler = _FakeAfterScheduler()
        cache = DeferredFluidWaveCache(
            scheduler.schedule,
            scheduler.cancel,
            lambda bucket, frame: (bucket, frame),
            lambda image: image,
            frame_count=1,
            batch_size=1,
            max_buckets=2,
        )

        for bucket in (20, 50):
            cache.request(bucket)
            scheduler.run_all()
        self.assertIsNotNone(cache.get(20))
        cache.request(75)
        scheduler.run_all()

        self.assertEqual((20, 70), cache.cached_buckets)
        self.assertIsNone(cache.get(50))

    def test_deferred_wave_cache_converts_frames_only_on_owner_thread(self) -> None:
        scheduler = _FakeAfterScheduler()
        converted_threads: list[int] = []
        cache = DeferredFluidWaveCache(
            scheduler.schedule,
            scheduler.cancel,
            lambda bucket, frame: (bucket, frame),
            lambda image: converted_threads.append(threading.get_ident()) or image,
            frame_count=1,
            batch_size=1,
        )
        cache.request(20)
        errors: list[BaseException] = []

        def run_off_thread() -> None:
            try:
                cache.process_next_batch()
            except BaseException as exception:
                errors.append(exception)

        worker = threading.Thread(target=run_off_thread)
        worker.start()
        worker.join()

        self.assertEqual([], converted_threads)
        self.assertEqual(1, len(errors))
        self.assertIsInstance(errors[0], RuntimeError)
        cache.close()

    def test_deferred_wave_cache_pauses_resumes_once_and_ignores_stale_callback_after_close(self) -> None:
        scheduler = _FakeAfterScheduler()
        rendered: list[tuple[int, int]] = []
        cache = DeferredFluidWaveCache(
            scheduler.schedule,
            scheduler.cancel,
            lambda bucket, frame: rendered.append((bucket, frame)) or (bucket, frame),
            lambda image: image,
            frame_count=4,
            batch_size=1,
        )
        cache.request(20)
        stale_callback = next(iter(scheduler.callbacks.values()))

        cache.pause()
        self.assertEqual(0, len(scheduler.callbacks))
        cache.resume()
        cache.resume()
        self.assertEqual(1, len(scheduler.callbacks))
        pending_id = cache.after_id
        cache.close()

        self.assertIn(pending_id, scheduler.cancelled)
        self.assertEqual(0, len(scheduler.callbacks))
        stale_callback()
        self.assertEqual([], rendered)
        self.assertFalse(cache.request(50))


if __name__ == "__main__":
    unittest.main()
