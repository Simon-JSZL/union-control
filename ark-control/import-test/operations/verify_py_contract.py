"""用实际 Java 序列化结果验证 py-app 类型、调用参数与完整记录契约。"""

import asyncio
import json
from pathlib import Path
import sys
from types import SimpleNamespace

control = Path(__file__).resolve().parents[2]
py_app = Path(sys.argv[1]).resolve()
sys.path.insert(0, str(py_app))

from app.tools import operations
from app.tools.operations_contracts import (
    AccessedApiQuery, ChangeQuery, DailyIndicatorQuery, FaultQuery,
    InstitutionQuery, SecurityCapabilityQuery, SystemCodeQuery,
)
from app.utils.api_client import ToolResult

QUERIES = {
    "changes": (operations.query_changes, ChangeQuery),
    "faults": (operations.query_faults, FaultQuery),
    "t_indicator_base_info": (operations.query_institutions, InstitutionQuery),
    "t_indicator_change_info": (operations.query_change_indicators, DailyIndicatorQuery),
    "t_indicator_change_info_all_biz": (operations.query_all_business_change_indicators, DailyIndicatorQuery),
    "t_indicator_fault_info": (operations.query_fault_indicators, DailyIndicatorQuery),
    "t_indicator_sys_code": (operations.query_system_code_indicators, SystemCodeQuery),
    "t_indicator_accessed_api_info": (operations.query_accessed_api_indicators, AccessedApiQuery),
    "t_indicator_operation_security_cap": (operations.query_operation_security_capabilities, SecurityCapabilityQuery),
}


async def verify():
    samples = json.loads((control / "target/operations-contract-samples.json").read_text())
    for sample in samples:
        request, page = sample["request"], sample["page"]
        name = request.get("interfaceName", sample["type"])
        function, model = QUERIES[name]
        query = model.model_validate(request.get("params", request))

        async def call(**kwargs):
            payload = kwargs["payload"]
            assert payload.get("params", payload) == page["applied_query"], name
            return ToolResult(status="success", data=page)

        ctx = SimpleNamespace(deps=SimpleNamespace(api_client=SimpleNamespace(call=call)))
        result = await function(ctx, query)
        assert len(result.data.items) == len(page["items"])
        assert result.data.coverage == "partial"  # Never label mock evidence as production coverage.
    print(f"Validated {len(samples)} Java response pages through actual py-app query tools.")
    fixture = json.loads((control / "src/main/resources/mock/operations-v1.json").read_text())
    catalog = json.loads((py_app / "app/tools/indicator_definitions.json").read_text())
    for table, data in fixture["indicators"].items():
        fields = {field["field"] for field in catalog["tables"][table]["fields"]}
        for item in data["items"]:
            assert set(item["values"]) == fields, table
    if len(sys.argv) > 2:
        docs = Path(sys.argv[2])
        notice = json.loads((docs / "notice-example-202601~08.txt").read_text())
        execution = json.loads((docs / "jira-example-202601~08.txt").read_text())
        notice_keys = set().union(*(row.keys() for row in notice))
        execution_keys = set().union(*(row.keys() for row in execution))
        for change in fixture["changes"]:
            raw = change["details"]["source_fields"]
            assert set(raw) - {"execution"} == notice_keys
            assert set(raw["execution"]) == execution_keys
            assert set(raw["closeChannelDTO"]) == set(notice[0]["closeChannelDTO"])
        fault_keys = set().union(*(item.keys() for row in execution if row.get("executeDesc") for item in json.loads(row["executeDesc"])))
        for fault in fixture["faults"]:
            assert set(fault["details"]["source_fields"]) == fault_keys
        print("Confirmed mock source field sets against actual notice/jira samples and seven-table catalog.")


asyncio.run(verify())
