import lldb

class TargetPlatform:
    def __init__(self, api, isArt):
        self._decoder = '_ZNK3art6Thread13DecodeJObjectEP8_jobject'
        self._thread = '_ZN3art6Thread14CurrentFromGdbEv'
        if api >= 23:
            self._length_offset = 8
            self._data_offset = 16
            self._container_offset = 0
            self._uses_container = False
        elif api >= 21:
            self._length_offset = 12
            self._data_offset = 12
            self._container_offset = 8
            self._uses_container = True
        elif api >= 19:
            self._data_offset = 12
            self._container_offset = 8
            self._uses_container = True
            if isArt:
                self._length_offset = 12
            else:
                self._length_offset = 20
                self._decoder = '_Z20dvmDecodeIndirectRefP6ThreadP8_jobject'
                self._thread = '_Z13dvmThreadSelfv'

    def length_offset(self):
        return self._length_offset

    def data_offset(self):
        return self._data_offset

    def container_offset(self):
        return self._container_offset

    def uses_container(self):
        return self._uses_container

    def decoder(self):
        return self._decoder

    def thread(self):
        return self._thread

class Reader:
    def __init__(self, valobj):
        self._decoder_expression = '(void*){0}((void*){1}(), (void*){2:#0x})'
        self._valobj = valobj
        self._target = self._valobj.GetTarget()
        self._process = self._valobj.GetProcess()

    def decode_string(self, platform):
        error = lldb.SBError()

        if not self._target.IsValid() or not self._process.IsValid():
            return ''

        jstring_val = self._valobj.GetValueAsUnsigned(error, 0)
        if error.Fail():
            return ''

        expression = self._decoder_expression.format(platform.decoder(), platform.thread(), jstring_val)
        options = lldb.SBExpressionOptions()
        options.SetTryAllThreads(False)
        mirror_string = self._target.EvaluateExpression(expression, options)
        if not mirror_string.IsValid():
            return ''

        mirror_string_address = mirror_string.GetValueAsUnsigned(error, 0)
        if error.Fail():
            return ''

        length_address = mirror_string_address + platform.length_offset()
        length = self._process.ReadUnsignedFromMemory(length_address, 4, error)
        if error.Fail():
            return ''
        elif length > 1024:
            length = 1024

        if platform.uses_container():
            container_address = mirror_string_address + platform.container_offset()
            container_pointer = self._process.ReadUnsignedFromMemory(container_address, 4, error)
            data_address = container_pointer + platform.data_offset()
        else:
            data_address = mirror_string_address + platform.data_offset()

        data = self._process.ReadMemory(data_address, 2 * length, error)
        if error.Fail():
            return ''

        return '"{}"'.format(bytearray(data).decode('utf-16'))

def jstring_summary_provider_23(valobj, internal_dict):
    return Reader(valobj).decode_string(TargetPlatform(23, True))

def jstring_summary_provider_21(valobj, internal_dict):
    return Reader(valobj).decode_string(TargetPlatform(21, True))

def jstring_summary_provider_19_art(valobj, internal_dict):
    return Reader(valobj).decode_string(TargetPlatform(19, True))

def jstring_summary_provider_19_dvm(valobj, internal_dict):
    return Reader(valobj).decode_string(TargetPlatform(19, False))

def use_function(function):
    jni_category = lldb.debugger.CreateCategory("JNI types")
    jstring_summary = lldb.SBTypeSummary.CreateWithFunctionName(function)
    jni_category.AddTypeSummary(lldb.SBTypeNameSpecifier("jstring"), jstring_summary)
    jni_category.SetEnabled(True)

def register(api, isDalvik):
    # If API < 19, we'll just show the jobject's address, which is what lldb currently does.
    if api >= 23:
        use_function("jstring_reader.jstring_summary_provider_23")
    elif api >= 21:
        use_function("jstring_reader.jstring_summary_provider_21")
    elif api >= 19:
        if isDalvik:
            use_function("jstring_reader.jstring_summary_provider_19_dvm")
        else:
            use_function("jstring_reader.jstring_summary_provider_19_art")
