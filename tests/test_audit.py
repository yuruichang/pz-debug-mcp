import importlib.util
from pathlib import Path
import unittest

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('interface_auditor', root / 'tools/audit_interfaces.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def method(key='A#get()I', effects=(), calls=(), **changes):
    value = {'id':key,'effects':list(effects),'calls':list(calls),'required_initialized':[], 'native':False,'abstract':False}
    value.update(changes)
    return value


class AuditTests(unittest.TestCase):
    def test_primitive_field_reader_is_proven(self):
        value = method()
        self.assertEqual(audit.classify(value, {value['id']:value})['status'], 'read_only_proven')

    def test_named_getter_with_side_effects_is_excluded(self):
        for effect in ('writes_field','writes_array'):
            value = method(effects=[effect])
            self.assertEqual(audit.classify(value, {value['id']:value})['status'], 'excluded_state_write')

    def test_factory_native_loop_and_throw_are_not_executed(self):
        for effect in ('allocates','dynamic_dispatch','loop_guard_needed','throws','synchronizes'):
            value = method(effects=[effect])
            self.assertEqual(audit.classify(value, {value['id']:value})['status'], 'requires_review')
        self.assertEqual(audit.classify(method(native=True), {})['status'], 'requires_review')

    def test_nullable_index_division_and_cast_need_context(self):
        for effect in ('nullable_receiver','array_index','division_guard_needed','cast_guard_needed'):
            self.assertEqual(audit.classify(method(effects=[effect]), {})['status'], 'requires_parameter_context')

    def test_dynamic_dependency_cannot_inherit_static_purity(self):
        child=method('B#read()I')
        parent=method(calls=[{'id':child['id'],'owner':'B','name':'read','fixed_dispatch':False}])
        self.assertEqual(audit.classify(parent,{child['id']:child})['status'],'requires_review')

    def test_fixed_dependency_writes_and_initialization_are_propagated(self):
        child=method('B#read()I', effects=['writes_field'])
        parent=method(calls=[{'id':child['id'],'owner':'B','name':'read','fixed_dispatch':True}])
        self.assertEqual(audit.classify(parent,{child['id']:child})['status'],'excluded_state_write')
        child=method('B#read()I', required_initialized=['C'])
        result=audit.classify(parent,{child['id']:child})
        self.assertEqual(result['status'],'read_only_proven')
        self.assertEqual(result['required_initialized'],['C'])

    def test_recursive_or_missing_dependency_is_not_proven(self):
        value=method(calls=[{'id':'A#get()I','owner':'A','name':'get','fixed_dispatch':True}])
        self.assertEqual(audit.classify(value,{value['id']:value})['status'],'requires_review')
        self.assertEqual(audit.classify(value,{})['status'],'requires_review')
