#[derive(Clone, PartialEq, ::prost::Message)]
pub struct Int32Value {
    #[prost(int32, tag = "1")]
    pub value: i32,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct Int64Value {
    #[prost(int64, tag = "1")]
    pub value: i64,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct UInt32Value {
    #[prost(uint32, tag = "1")]
    pub value: u32,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct UInt64Value {
    #[prost(uint64, tag = "1")]
    pub value: u64,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct FloatValue {
    #[prost(float, tag = "1")]
    pub value: f32,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct DoubleValue {
    #[prost(double, tag = "1")]
    pub value: f64,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct BoolValue {
    #[prost(bool, tag = "1")]
    pub value: bool,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct StringValue {
    #[prost(string, tag = "1")]
    pub value: String,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct BytesValue {
    #[prost(bytes, tag = "1")]
    pub value: Vec<u8>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ArrayValue {
    #[prost(message, repeated, tag = "1")]
    pub values: Vec<prost_types::Any>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct MapValue {
    #[prost(map = "string, message", tag = "1")]
    pub values: std::collections::HashMap<String, prost_types::Any>,
}
