use proc_macro::TokenStream;
use quote::{format_ident, quote};
use syn::{parse_macro_input, ItemFn};

#[proc_macro_attribute]
pub fn task(_attr: TokenStream, item: TokenStream) -> TokenStream {
    let input = parse_macro_input!(item as ItemFn);
    let fn_name = &input.sig.ident;
    let fn_name_str = fn_name.to_string();
    let vis = &input.vis;
    let block = &input.block;
    let inputs = &input.sig.inputs;

    let is_async = input.sig.asyncness.is_some();
    let register_fn = if is_async {
        format_ident!("register_async_task")
    } else {
        format_ident!("register_sync_task")
    };

    let register_fn_name = format_ident!("__register_task_{}", fn_name);

    let expanded = if is_async {
        quote! {
            #vis fn #fn_name(#inputs) -> impl std::future::Future<Output = String> + Send {
                async move { #block }
            }

            #[ctor::ctor]
            fn #register_fn_name() {
                use crate::tasks::REGISTRY;
                REGISTRY.#register_fn(#fn_name_str, |args| Box::pin(#fn_name(args)));
                println!("Auto-registered async task: {}", #fn_name_str);
            }
        }
    } else {
        quote! {
            #vis fn #fn_name(#inputs) -> String #block

            #[ctor::ctor]
            fn #register_fn_name() {
                use crate::tasks::REGISTRY;
                REGISTRY.#register_fn(#fn_name_str, #fn_name);
                println!("Auto-registered sync task: {}", #fn_name_str);
            }
        }
    };

    TokenStream::from(expanded)
}
