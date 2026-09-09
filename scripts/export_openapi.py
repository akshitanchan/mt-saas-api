# regenerate with: docker compose -p mt-saas-api run --rm --no-deps -e RUN_MIGRATIONS=0 api python -m scripts.export_openapi > contract/openapi.json
import json

from app.main import create_app

def main() -> int:
    spec = create_app().openapi()
    print(json.dumps(spec, indent=2, sort_keys=True))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
