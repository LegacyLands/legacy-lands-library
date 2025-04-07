use proc_macro::TokenStream;
use quote::{format_ident, quote};
use syn::{parse_macro_input, ItemFn, LitStr};

#[proc_macro_attribute]
pub fn async_task(_attr: TokenStream, item: TokenStream) -> TokenStream {
    let input = parse_macro_input!(item as ItemFn);
    let fn_name = &input.sig.ident;
    let task_name_str = fn_name.to_string();
    let task_name = LitStr::new(&task_name_str, fn_name.span());
    let internal_register_fn_name = format_ident!("__internal_register_task_{}", fn_name);
    let ctor_fn_name = format_ident!("__ctor_auto_register_{}", fn_name);

    let expanded = quote! {
        #input

        fn #internal_register_fn_name() {
            let task_closure = |args| -> std::pin::Pin<Box<dyn std::future::Future<Output = String> + Send>> {
                Box::pin(#fn_name(args))
            };
            crate::tasks::REGISTRY.register_async_task(#task_name, task_closure);
        }

        #[::ctor::ctor]
        fn #ctor_fn_name() {
            #internal_register_fn_name();
            match crate::tasks::PENDING_REGISTRATIONS.lock() {
                Ok(mut list) => list.push(crate::tasks::RegistrationInfo {
                    task_type: "async".to_string(),
                    task_name: #task_name_str.to_string(),
                }),
                Err(e) => {
                    eprintln!("Failed to lock PENDING_REGISTRATIONS for async task {}: {}", #task_name_str, e);
                }
            }
        }
    };

    TokenStream::from(expanded)
}

#[proc_macro_attribute]
pub fn sync_task(_attr: TokenStream, item: TokenStream) -> TokenStream {
    let input = parse_macro_input!(item as ItemFn);
    let fn_name = &input.sig.ident;
    let task_name_str = fn_name.to_string();
    let task_name = LitStr::new(&task_name_str, fn_name.span());
    let internal_register_fn_name = format_ident!("__internal_register_task_{}", fn_name);
    let ctor_fn_name = format_ident!("__ctor_auto_register_{}", fn_name);

    let expanded = quote! {
        #input

        fn #internal_register_fn_name() {
            crate::tasks::REGISTRY.register_sync_task(#task_name, #fn_name);
        }

        #[::ctor::ctor]
        fn #ctor_fn_name() {
            #internal_register_fn_name();
            match crate::tasks::PENDING_REGISTRATIONS.lock() {
                Ok(mut list) => list.push(crate::tasks::RegistrationInfo {
                    task_type: "sync".to_string(),
                    task_name: #task_name_str.to_string(),
                }),
                Err(e) => {
                    eprintln!("Failed to lock PENDING_REGISTRATIONS for sync task {}: {}", #task_name_str, e);
                }
            }
        }
    };

    TokenStream::from(expanded)
}
