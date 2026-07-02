import unittest

from tools import validate_openapi


class ValidateOpenApiContractTest(unittest.TestCase):
    def test_ai_build_mvp_paths_are_required(self) -> None:
        for path in [
            "/api/ai/build-chat",
            "/api/quote-drafts/current/apply-ai-build",
        ]:
            with self.subTest(path=path):
                self.assertIn(path, validate_openapi.REQUIRED_PATHS)

    def test_ai_build_mvp_request_schemas_are_checked(self) -> None:
        self.assertEqual(
            validate_openapi.POST_JSON_REQUEST_SCHEMAS.get("/api/ai/build-chat"),
            "AiBuildChatRequest",
        )
        self.assertEqual(
            validate_openapi.PUT_JSON_REQUEST_SCHEMAS.get(
                "/api/quote-drafts/current/apply-ai-build"
            ),
            "AiBuildApplyRequest",
        )

    def test_ai_build_mvp_schemas_are_required(self) -> None:
        for schema_name in [
            "AiBuildChatRequest",
            "AiBuildChatResponse",
            "AiBuildApplyRequest",
            "QuoteDraftDto",
        ]:
            with self.subTest(schema_name=schema_name):
                self.assertIn(schema_name, validate_openapi.REQUIRED_SCHEMAS)


if __name__ == "__main__":
    unittest.main()
