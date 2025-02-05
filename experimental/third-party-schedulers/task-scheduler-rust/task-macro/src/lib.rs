use proc_macro::TokenStream;
use quote::quote;
use syn::{parse_macro_input, ItemFn};

#[proc_macro_attribute]
pub fn task(_attr: TokenStream, item: TokenStream) -> TokenStream {
    let input = parse_macro_input!(item as ItemFn);
    let fn_name = &input.sig.ident;
    let is_async = input.sig.asyncness.is_some();
    let register_fn_name = quote::format_ident!("register_{}", fn_name);

    let register_code = if is_async {
        quote! {
            #input

            #[ctor::ctor]
            fn #register_fn_name() {
                use crate::tasks::REGISTRY;
                use std::pin::Pin;
                use std::future::Future;
                let key = format!("{}::{}", module_path!(), stringify!(#fn_name));
                let func = |args| {
                    let fut = #fn_name(args);
                    Box::pin(fut) as Pin<Box<dyn Future<Output = String> + Send>>
                };
                REGISTRY.register_async_task(&key, func);
            }
        }
    } else {
        quote! {
            #input

            #[ctor::ctor]
            fn #register_fn_name() {
                use crate::tasks::REGISTRY;
                let key = format!("{}::{}", module_path!(), stringify!(#fn_name));
                REGISTRY.register_sync_task(&key, #fn_name);
            }
        }
    };

    register_code.into()
}
